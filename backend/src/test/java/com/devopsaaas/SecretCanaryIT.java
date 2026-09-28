package com.devopsaaas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.devopsaaas.identity.Role;
import com.devopsaaas.llm.InterceptingLlmGateway;
import com.devopsaaas.support.AgentTestSupport;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import com.devopsaaas.tool.container.ContainerSnapshot;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import com.devopsaaas.tool.container.HealthStatus;
import com.devopsaaas.tool.container.LogLine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Slice 9a, secrets as canaries through the whole flow: the system's own secrets (JWT signing key, bootstrap
 * password, a user's password and bearer token) and a third-party secret inside the container's logs go
 * through login, diagnosis, approval, restart (one that succeeds and one whose outcome is unknown, which logs
 * a warning with its stack trace), explanation and audit. None of them may appear in the application's log
 * events (message, MDC, stack trace), in any HTTP response, in any row the flow wrote, or in what was sent to
 * the LLM.
 * The adapters' own error paths are covered where they live: DockerEngineContainerRuntimeTest
 * (httpErrors_becomeCategories_withoutDockerMessages) and OpenAiLlmAdapterTest
 * (theApiKey_neverAppearsInLogsOrErrors).
 */
class SecretCanaryIT extends AgentTestSupport {

    private static final String LOG_SECRET = "canary-log-S3cr3t-value";
    private static final String LOG_BEARER = "canaryBearerToken1234567890";
    private static final String WRONG_PASSWORD = "canary-wrong-password-987";

    @Autowired
    FakeContainerRuntime runtime;

    @Autowired
    InterceptingLlmGateway llm;

    @Test
    void noSecret_leavesThroughLogsHttpDatabaseOrTheModel() {
        // A logback appender on the root logger sees every event whatever the test order; capturing stdout
        // does not, because the console appender keeps the stream it got when the shared context started.
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            run(logs);
        } finally {
            root.detachAppender(logs);
        }
    }

    private void run(ListAppender<ILoggingEvent> logs) {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        TestUser operator = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);
        TestUser approver = createUser(DEFAULT_ORGANIZATION, Role.APPROVER);
        String container = uniqueName("canary-container");
        String environment = environment(admin, "ASSISTED", container);
        runtime.setSnapshot(container, new ContainerSnapshot(container, ContainerState.RUNNING, HealthStatus.UNHEALTHY,
                null, false, 0, Instant.now().minusSeconds(3600), null, "demo:latest"));
        runtime.setLogs(container, List.of(
                new LogLine(Instant.now().minusSeconds(2), "stderr", "db password=" + LOG_SECRET + " rejected"),
                new LogLine(Instant.now().minusSeconds(1), "stdout", "calling upstream with Bearer " + LOG_BEARER)));
        List<String> responses = new ArrayList<>();

        // Logins: a correct one and a wrong password, both carried in request bodies. A login answers with a
        // token by design, so these two responses are checked for everything but the users' tokens.
        List<String> logins = List.of(
                post("/api/v1/auth/login", null, Map.of("email", operator.email(), "password", PASSWORD)).getBody(),
                post("/api/v1/auth/login", null, Map.of("email", operator.email(), "password", WRONG_PASSWORD))
                        .getBody());

        String question = "Por favor reinicie a demo-api " + uniqueName("canary");
        JsonNode waiting = ask(operator, conversation(operator, environment), question);
        String approvalId = waiting.get("approvals").get(0).get("approvalId").asString();
        responses.add(get("/api/v1/approvals/" + approvalId, approver.token()).getBody());
        responses.add(post("/api/v1/approvals/" + approvalId + "/decision", approver.token(),
                Map.of("decision", "APPROVE")).getBody());
        String executionId = waiting.get("executionId").asString();
        JsonNode finished = await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> read(get("/api/v1/executions/" + executionId, operator.token())),
                        view -> view.get("status").asString().equals("COMPLETED"));
        responses.add(finished.toString());
        for (JsonNode action : actions(finished)) {
            responses.add(get("/api/v1/tool-executions/" + action.get("toolExecutionId").asString(),
                    operator.token()).getBody());
        }
        ResponseEntity<String> audit = get("/api/v1/audit-events?agentExecutionId=" + executionId + "&size=200",
                admin.token());
        responses.add(audit.getBody());

        // A second restart whose connection drops after the request: the executor logs it with the stack trace.
        runtime.failRestartWith(container, ContainerRuntimeException.Category.UNAVAILABLE);
        String unknownQuestion = "Por favor reinicie a demo-api " + uniqueName("canary-unknown");
        JsonNode unknown = ask(operator, conversation(operator, environment), unknownQuestion);
        responses.add(post("/api/v1/approvals/" + unknown.get("approvals").get(0).get("approvalId").asString()
                + "/decision", approver.token(), Map.of("decision", "APPROVE")).getBody());
        String unknownId = unknown.get("executionId").asString();
        responses.add(await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> read(get("/api/v1/executions/" + unknownId, operator.token())),
                        view -> view.get("status").asString().equals("COMPLETED")).toString());

        List<String> canaries = List.of(JWT_SECRET, BOOTSTRAP_PASSWORD, PASSWORD, WRONG_PASSWORD, LOG_SECRET,
                LOG_BEARER, operator.token(), approver.token(), admin.token());
        String database = String.join("\n", jdbc.queryForList("""
                SELECT row_to_json(t)::text FROM audit_event t WHERE t.organization_id = ?
                UNION ALL SELECT row_to_json(t)::text FROM tool_execution t
                    WHERE t.agent_execution_id IN (?::uuid, ?::uuid)
                UNION ALL SELECT row_to_json(t)::text FROM approval t WHERE t.agent_execution_id IN (?::uuid, ?::uuid)
                UNION ALL SELECT row_to_json(t)::text FROM agent_execution t WHERE t.id IN (?::uuid, ?::uuid)
                UNION ALL SELECT row_to_json(t)::text FROM llm_call t WHERE t.agent_execution_id IN (?::uuid, ?::uuid)
                UNION ALL SELECT row_to_json(t)::text FROM message t WHERE t.organization_id = ?
                """, String.class, operator.organizationId(), executionId, unknownId, executionId, unknownId,
                executionId, unknownId, executionId, unknownId, operator.organizationId()));
        String model = llm.requestsFor(question).toString() + llm.requestsFor(unknownQuestion);

        // The flow really carried the log secrets to where they are masked: the canaries are not vacuous.
        assertThat(database).contains("<redacted");
        assertThat(model).contains("<redacted");
        String applicationLogs = logs.list.stream()
                .map(event -> event.getLoggerName() + " " + event.getFormattedMessage() + " "
                        + event.getMDCPropertyMap() + " " + (event.getThrowableProxy() == null ? ""
                                : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(Collectors.joining("\n"));
        assertThat(applicationLogs).as("the unknown outcome was logged, with its stack trace")
                .contains("outcome unknown").contains("ContainerRuntimeException");
        for (String canary : List.of(JWT_SECRET, BOOTSTRAP_PASSWORD, PASSWORD, WRONG_PASSWORD)) {
            assertThat(String.join("\n", logins)).as("login responses").doesNotContain(canary);
        }
        for (String canary : canaries) {
            assertThat(applicationLogs).as("application logs").doesNotContain(canary);
            assertThat(String.join("\n", responses)).as("HTTP responses").doesNotContain(canary);
            assertThat(database).as("rows written by the flow").doesNotContain(canary);
            assertThat(model).as("requests sent to the LLM").doesNotContain(canary);
        }
    }
}
