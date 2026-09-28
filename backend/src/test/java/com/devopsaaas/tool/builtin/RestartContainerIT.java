package com.devopsaaas.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.devopsaaas.identity.Role;
import com.devopsaaas.llm.InterceptingLlmGateway;
import com.devopsaaas.llm.LlmMessage;
import com.devopsaaas.support.AgentTestSupport;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import com.devopsaaas.tool.container.ContainerSnapshot;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import com.devopsaaas.tool.container.HealthStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Slice 8 end to end, through the HTTP API: the scripted demo ({@code demo-fix}) checks status and logs,
 * proposes {@code restartContainer}, a human approves, the backend restarts and verifies, and the action can
 * be explained afterwards from the records alone (RF-46, H3).
 */
class RestartContainerIT extends AgentTestSupport {

    private static final List<String> NOT_FINISHED = List.of("QUEUED", "RUNNING", "WAITING_APPROVAL");
    private static final String REASON =
            "The user asked to fix demo-api; the status and logs were checked first (scripted demo).";

    @Autowired
    FakeContainerRuntime runtime;

    @Autowired
    InterceptingLlmGateway llm;

    private TestUser admin;
    private TestUser operator;
    private TestUser approver;
    private String container;
    private String environment;

    @BeforeEach
    void setUp() {
        admin = createAdmin(DEFAULT_ORGANIZATION);
        operator = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);
        approver = createUser(DEFAULT_ORGANIZATION, Role.APPROVER);
        container = uniqueName("restart-container");
        environment = environment(admin, "ASSISTED", container);
    }

    /** The demonstration of the slice: diagnose, propose, approve, restart, verify, explain. */
    @Test
    void anApprovedRestart_restartsOnce_isVerified_andCanBeExplained() {
        runtime.setSnapshot(container, new ContainerSnapshot(container, ContainerState.EXITED, HealthStatus.NONE, 42,
                false, 0, Instant.now().minusSeconds(600), Instant.now().minusSeconds(60), "demo:latest"));
        String question = question();
        JsonNode waiting = ask(operator, conversation(operator, environment), question);

        assertThat(waiting.get("status").asString()).isEqualTo("WAITING_APPROVAL");
        assertThat(actions(waiting)).extracting(RestartContainerIT::toolAndStatus).containsExactly(
                "getContainerStatus:SUCCEEDED", "getContainerLogs:SUCCEEDED", "restartContainer:WAITING_APPROVAL");
        assertThat(restarts()).isZero();

        JsonNode approval = approvalOf(waiting);
        assertThat(approval.get("action").get("tool").asString()).isEqualTo("restartContainer");
        assertThat(approval.get("action").get("target").asString()).isEqualTo("demo-api");
        assertThat(approval.get("system").get("riskLevel").asString()).isEqualTo("HIGH_RISK");
        assertThat(approval.get("system").get("impact").asString()).isEqualTo(RestartContainerTool.IMPACT);
        // The evidence is what the backend observed before, not what the model says.
        assertThat(approval.get("system").get("evidence").valueStream().map(e -> e.get("code").asString()))
                .contains("EXITED_WITH_ERROR");
        // The reason argument is the justification shown to the approver: untrusted, and bound to the hash.
        assertThat(approval.get("agentClaims").get("justification").asString()).isEqualTo(REASON);
        assertThat(approval.get("agentClaims").get("trusted").asBoolean()).isFalse();
        assertThat(approval.get("action").get("arguments").get("reason").asString()).isEqualTo(REASON);

        assertThat(decide(approver, approval.get("approvalId").asString(), "APPROVE", "Approved for the demo.")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode finished = finished(operator, waiting.get("executionId").asString());
        assertThat(finished.get("status").asString()).isEqualTo("COMPLETED");
        JsonNode restart = actions(finished).getLast();
        assertThat(toolAndStatus(restart)).isEqualTo("restartContainer:SUCCEEDED");
        assertThat(restarts()).isEqualTo(1);

        JsonNode explanation = explain(operator, restart.get("toolExecutionId").asString());
        JsonNode result = explanation.get("result").get("output");
        JsonNode output = result.get("data");
        assertThat(result.get("findings").isEmpty()).isTrue();
        assertThat(output.get("stateBefore").asString()).isEqualTo("EXITED");
        assertThat(output.get("stateAfter").asString()).isEqualTo("RUNNING");
        assertThat(output.get("restartObserved").asBoolean()).isTrue();
        assertThat(output.get("verification").asString()).isEqualTo("HEALTHY");

        // Who asked, what they asked, what had been observed, why, who approved, when, and what came out.
        assertThat(explanation.get("request").get("requestedBy").asString()).isEqualTo(operator.id().toString());
        assertThat(explanation.get("request").get("question").asString()).isEqualTo(question);
        assertThat(explanation.get("action").get("tool").asString()).isEqualTo("restartContainer");
        assertThat(explanation.get("action").get("target").asString()).isEqualTo("demo-api");
        assertThat(explanation.get("action").get("risk").asString()).isEqualTo("HIGH_RISK");
        assertThat(explanation.get("observations").valueStream().map(RestartContainerIT::toolAndStatus))
                .containsExactly("getContainerStatus:SUCCEEDED", "getContainerLogs:SUCCEEDED");
        assertThat(explanation.get("observations").get(0).get("findings").valueStream().map(JsonNode::asString))
                .contains("EXITED_WITH_ERROR");
        assertThat(explanation.get("agentClaims").get("trusted").asBoolean()).isFalse();
        assertThat(explanation.get("approval").get("status").asString()).isEqualTo("APPROVED");
        assertThat(explanation.get("approval").get("decision").get("decidedBy").asString())
                .isEqualTo(approver.id().toString());
        assertThat(explanation.get("approval").get("decision").get("decidedAt").isNull()).isFalse();
        assertThat(explanation.get("result").get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(explanation.get("audit").valueStream().map(line -> line.get("action").asString()))
                .containsSubsequence("APPROVAL_REQUESTED", "APPROVAL_GRANTED", "TOOL_EXECUTION_SUCCEEDED");
        assertThat(explanation.get("audit").valueStream()
                .filter(line -> line.get("action").asString().equals("APPROVAL_GRANTED"))
                .map(line -> line.get("actorType").asString() + ":" + line.get("actorLabel").asString()))
                .containsExactly("USER:" + approver.email());

        // The audit trail of the service, narrowed to this tool.
        JsonNode trail = read(get("/api/v1/audit-events?resourceType=SERVICE&resourceId=" + serviceId()
                + "&toolName=restartContainer", admin.token()));
        assertThat(trail.get("items").valueStream().map(event -> event.get("toolName").asString()))
                .isNotEmpty().containsOnly("restartContainer");
        assertThat(trail.get("items").valueStream().map(event -> event.get("action").asString()))
                .contains("TOOL_EXECUTION_SUCCEEDED");
        JsonNode byExecution = read(get("/api/v1/audit-events?agentExecutionId=" + waiting.get("executionId")
                .asString() + "&size=200", admin.token()));
        assertThat(byExecution.get("items").valueStream().map(event -> event.get("agentExecutionId").asString()))
                .isNotEmpty().containsOnly(waiting.get("executionId").asString());
    }

    /**
     * OUTCOME_UNKNOWN means "we do not know whether the restart happened", NOT "the restart failed": the
     * request may have reached Docker before the connection dropped. So it is never FAILED, never retried
     * (a second restart could hit a container that already came back), and the model is told only that the
     * outcome is unknown.
     */
    @Test
    void theConnectionDroppingAfterTheRequest_isOutcomeUnknown_notFailed_andIsNeverRetried() {
        runtime.failRestartWith(container, ContainerRuntimeException.Category.UNAVAILABLE);
        String question = question();
        JsonNode waiting = ask(operator, conversation(operator, environment), question);

        decide(approver, approvalOf(waiting).get("approvalId").asString(), "APPROVE", null);

        JsonNode finished = finished(operator, waiting.get("executionId").asString());
        JsonNode restart = actions(finished).getLast();
        assertThat(restart.get("tool").asString()).isEqualTo("restartContainer");
        assertThat(restart.get("status").asString())
                .as("unknown whether the restart took effect: not FAILED, which would claim it did not happen")
                .isEqualTo("OUTCOME_UNKNOWN")
                .isNotEqualTo("FAILED");
        assertThat(restarts()).as("exactly one restart request, no retry").isEqualTo(1);

        String told = toolResults(question).getLast();
        assertThat(told).contains("\"status\":\"OUTCOME_UNKNOWN\"").contains("may have reached")
                .doesNotContain("FAILED").doesNotContain("SUCCEEDED");
        JsonNode explanation = explain(operator, restart.get("toolExecutionId").asString());
        assertThat(explanation.get("result").get("status").asString()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(explanation.get("result").get("output").isNull()).isTrue();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tool_execution_id = ?::uuid AND action = "
                + "'TOOL_EXECUTION_OUTCOME_UNKNOWN'", restart.get("toolExecutionId").asString())).isEqualTo(1);
    }

    /** The restart happened; what came back is unhealthy. That is a finding about the service, not FAILED. */
    @Test
    void aRestartThatComesBackUnhealthy_succeeds_withTheRestartUnverifiedFinding() {
        runtime.afterRestart(container, FakeContainerRuntime.AfterRestart.UNHEALTHY);
        JsonNode waiting = ask(operator, conversation(operator, environment), question());

        decide(approver, approvalOf(waiting).get("approvalId").asString(), "APPROVE", null);

        JsonNode restart = actions(finished(operator, waiting.get("executionId").asString())).getLast();
        assertThat(toolAndStatus(restart)).isEqualTo("restartContainer:SUCCEEDED");
        JsonNode result = explain(operator, restart.get("toolExecutionId").asString()).get("result").get("output");
        JsonNode output = result.get("data");
        assertThat(output.get("restartObserved").asBoolean()).isTrue();
        assertThat(output.get("verification").asString()).isEqualTo("UNHEALTHY");
        assertThat(result.get("findings").valueStream().map(f -> f.get("code").asString() + ":"
                + f.get("severity").asString())).containsExactly("RESTART_UNVERIFIED:HIGH");
        assertThat(restarts()).isEqualTo(1);
    }

    @Test
    void aRejectedRestart_neverReachesTheRuntime() {
        JsonNode waiting = ask(operator, conversation(operator, environment), question());

        decide(approver, approvalOf(waiting).get("approvalId").asString(), "REJECT", "Not now.");

        JsonNode restart = actions(finished(operator, waiting.get("executionId").asString())).getLast();
        assertThat(toolAndStatus(restart)).isEqualTo("restartContainer:REJECTED");
        assertThat(restarts()).isZero();
    }

    /** OBSERVE_ONLY never even asks: the policy denies the proposal before any approval exists. */
    @Test
    void inObserveOnly_theRestartIsDenied_withoutAnApproval() {
        String observedContainer = uniqueName("observe-container");
        String observeOnly = environment(admin, "OBSERVE_ONLY", observedContainer);
        JsonNode finished = finished(operator, ask(operator, conversation(operator, observeOnly), question())
                .get("executionId").asString());

        JsonNode restart = actions(finished).getLast();
        assertThat(toolAndStatus(restart)).isEqualTo("restartContainer:DENIED");
        assertThat(restart.get("denialReason").asString()).isEqualTo("NOT_ALLOWED_BY_AUTONOMY");
        assertThat(finished.get("approvals").isEmpty()).isTrue();
        assertThat(runtime.calls()).doesNotContain("restart:" + observedContainer);
    }

    @Test
    void theExplanation_isOnlyForTheCallersOrganization_andNeedsExecutionRead() {
        JsonNode waiting = ask(operator, conversation(operator, environment), question());
        String toolExecution = actions(waiting).getLast().get("toolExecutionId").asString();

        TestUser outsider = createUser(createOrganization(), Role.ADMIN);
        assertThat(get("/api/v1/tool-executions/" + toolExecution, outsider.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/tool-executions/" + UUID.randomUUID(), operator.token())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/tool-executions/" + toolExecution, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Pending: the explanation already shows the proposal and the approval waiting for a human.
        JsonNode pending = explain(approver, toolExecution);
        assertThat(pending.get("result").get("status").asString()).isEqualTo("WAITING_APPROVAL");
        assertThat(pending.get("approval").get("status").asString()).isEqualTo("PENDING");
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** demo-fix answers any question asking to fix or restart; unique so recorded model requests never mix. */
    private static String question() {
        return "Por favor reinicie a demo-api " + uniqueName("q");
    }

    private ResponseEntity<String> decide(TestUser user, String approvalId, String decision, String comment) {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        body.put("comment", comment);
        return post("/api/v1/approvals/" + approvalId + "/decision", user.token(), body);
    }

    private JsonNode approvalOf(JsonNode execution) {
        String approvalId = execution.get("approvals").get(0).get("approvalId").asString();
        return read(get("/api/v1/approvals/" + approvalId, approver.token()));
    }

    private JsonNode explain(TestUser user, String toolExecutionId) {
        ResponseEntity<String> response = get("/api/v1/tool-executions/" + toolExecutionId, user.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return read(response);
    }

    private JsonNode finished(TestUser user, String executionId) {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> read(get("/api/v1/executions/" + executionId, user.token())),
                        view -> !NOT_FINISHED.contains(view.get("status").asString()));
    }

    private String serviceId() {
        return jdbc.queryForObject("SELECT id::text FROM environment_service "
                + "WHERE environment_id = ?::uuid AND name = 'demo-api'", String.class, environment);
    }

    private long restarts() {
        return runtime.calls().stream().filter(call -> call.equals("restart:" + container)).count();
    }

    private List<String> toolResults(String marker) {
        return llm.requestsFor(marker).getLast().messages().stream()
                .filter(LlmMessage.ToolResult.class::isInstance)
                .map(message -> ((LlmMessage.ToolResult) message).content())
                .toList();
    }

    private static String toolAndStatus(JsonNode action) {
        return action.get("tool").asString() + ":" + action.get("status").asString();
    }
}
