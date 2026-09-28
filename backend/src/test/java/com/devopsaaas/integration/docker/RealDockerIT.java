package com.devopsaaas.integration.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.devopsaaas.identity.Role;
import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.llm.InterceptingLlmGateway;
import com.devopsaaas.llm.LlmMessage;
import com.devopsaaas.llm.LlmRequest;
import com.devopsaaas.support.AgentTestSupport;
import com.devopsaaas.tool.container.ContainerRef;
import com.devopsaaas.tool.container.ContainerRuntime;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import com.devopsaaas.tool.execution.ToolExecutionOutcome;
import com.devopsaaas.tool.execution.ToolExecutionRequest;
import com.devopsaaas.tool.execution.ToolExecutionStatus;
import com.devopsaaas.tool.execution.ToolExecutor;
import com.devopsaaas.tool.policy.DenialReason;
import com.devopsaaas.tool.policy.PolicyContext;
import com.devopsaaas.tool.policy.ToolProposal;
import com.devopsaaas.tool.testing.TestToolsConfiguration;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;

/**
 * The whole chain against a real Docker Engine: policy → executor → tool → adapter → docker-socket-proxy →
 * Docker. The proxy runs with the image and configuration of docker-compose.yml, and every tool call here
 * crosses it; its request log is the proof of what reached Docker.
 *
 * <p>Next to the allowlisted target runs an intruder container, on the same host and visible to the same
 * proxy. It shows the two layers apart: the proxy limits what the API may do (it would happily serve the
 * intruder's inspect), and the allowlist, enforced by the backend, limits which containers the agent may use.
 */
@TestPropertySource(properties = TestToolsConfiguration.REAL_RUNTIME_PROPERTY + "=true")
class RealDockerIT extends AgentTestSupport {

    /** Same image and digest as docker-compose.yml; {@link #theProxyHereIsTheOneFromCompose()} keeps them equal. */
    static final String PROXY_IMAGE = "linuxserver/socket-proxy:3.4.5"
            + "@sha256:ca6c0301a652232d02cb0ff9f1c5d20d9b339f391e1c1dffaae6ef3bfc59d009";
    private static final String TARGET_IMAGE = "alpine:3.22";

    private static final String SUFFIX = Long.toString(System.nanoTime(), 36);
    static final String TARGET_NAME = "devops-it-target-" + SUFFIX;
    static final String INTRUDER_NAME = "devops-it-intruder-" + SUFFIX;

    private static final String ENV_SECRET = "it-db-password-not-real"; // gitleaks:allow (fake fixture)
    private static final String INTRUDER_SECRET = "intruder-secret-not-real"; // gitleaks:allow (fake fixture)
    private static final String LOG_JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJpdCJ9.aXQtc2lnbmF0dXJlLW5vdC1yZWFs"; // gitleaks:allow (fake fixture)
    private static final String LOG_PASSWORD = "it-log-password-not-real"; // gitleaks:allow (fake fixture)

    @SuppressWarnings("resource")
    static final GenericContainer<?> PROXY = new GenericContainer<>(PROXY_IMAGE)
            .withEnv(Map.of(
                    "CONTAINERS", "1",
                    "ALLOW_LOGS", "1",
                    "ALLOW_RESTARTS", "0",
                    "POST", "0",
                    "EVENTS", "0",
                    "PING", "1",
                    "VERSION", "1",
                    "DISABLE_IPV6", "1"))
            .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock", BindMode.READ_ONLY)
            .withTmpFs(Map.of("/run", "rw", "/tmp", "rw"))
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withReadonlyRootfs(true)
                    .withCapDrop(Capability.ALL)
                    .withSecurityOpts(List.of("no-new-privileges:true")))
            .withExposedPorts(2375)
            .waitingFor(Wait.forHttp("/v1.44/_ping").forStatusCode(200));

    @SuppressWarnings("resource")
    static final GenericContainer<?> TARGET = new GenericContainer<>(TARGET_IMAGE)
            .withEnv("DB_PASSWORD", ENV_SECRET)
            .withCommand("sh", "-c", "echo 'target ready'; echo 'login ok token " + LOG_JWT + "'; "
                    + "echo 'connecting with password=" + LOG_PASSWORD + "' 1>&2; exec sleep 3600")
            .withCreateContainerCmdModifier(cmd -> cmd.withName(TARGET_NAME))
            .waitingFor(Wait.forLogMessage(".*target ready.*", 1));

    @SuppressWarnings("resource")
    static final GenericContainer<?> INTRUDER = new GenericContainer<>(TARGET_IMAGE)
            .withEnv("INTRUDER_SECRET", INTRUDER_SECRET)
            .withCommand("sh", "-c", "echo 'intruder ready'; exec sleep 3600")
            .withCreateContainerCmdModifier(cmd -> cmd.withName(INTRUDER_NAME))
            .waitingFor(Wait.forLogMessage(".*intruder ready.*", 1));

    static {
        PROXY.start();
        TARGET.start();
        INTRUDER.start();
    }

    @AfterAll
    static void stopContainers() {
        INTRUDER.stop();
        TARGET.stop();
        PROXY.stop();
    }

    @DynamicPropertySource
    static void runtime(DynamicPropertyRegistry registry) {
        registry.add("devops.runtime.connections.local.docker-url", RealDockerIT::proxyUrl);
    }

    static String proxyUrl() {
        return "http://" + PROXY.getHost() + ":" + PROXY.getMappedPort(2375);
    }

    @Autowired
    ToolExecutor executor;

    @Autowired
    ContainerRuntime runtime;

    @Autowired
    InterceptingLlmGateway llm;

    private TestUser admin;
    private TestUser operator;
    private UUID environment;

    @BeforeEach
    void environment() {
        admin = createAdmin(DEFAULT_ORGANIZATION);
        operator = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);
        environment = UUID.fromString(createEnvironment(admin, uniqueName("docker")).get("id").asString());
        allowlistService(admin, environment.toString(), "demo-api", TARGET_NAME);
    }

    @Test
    void theToolsTalkToTheRealAdapter_throughTheProxy() {
        assertThat(runtime).isInstanceOf(DockerEngineContainerRuntime.class);

        ToolExecutionOutcome outcome = run("getContainerStatus", "{\"service\":\"demo-api\"}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(data(outcome).get("state").asString()).isEqualTo("RUNNING");
        assertThat(data(outcome).get("image").asString()).isEqualTo(TARGET_IMAGE);
        assertThat(proxyRequestLog()).contains("GET /v1.44/containers/" + TARGET_NAME + "/json");
    }

    // ---- 1. the intruder ---------------------------------------------------------------------------------

    @Test
    void aContainerOutsideTheAllowlist_isNeverListedNorResolvable_andTheProxyNeverSeesARequestForIt() {
        int logBefore = proxyRequestLog().length();
        ToolExecutionOutcome list = run("listContainers", "{}");
        assertThat(list.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        JsonNode services = data(list).get("services");
        assertThat(services.size()).isEqualTo(1);
        assertThat(services.get(0).get("service").asString()).isEqualTo("demo-api");
        assertThat(services.get(0).get("state").asString()).isEqualTo("RUNNING");

        // By logical name, by its real container name, and by its container ID: all refused by the policy.
        String intruderId = INTRUDER.getContainerId();
        for (String service : List.of("intruder", INTRUDER_NAME, intruderId.substring(0, 12))) {
            for (String tool : List.of("getContainerStatus", "getContainerLogs")) {
                ToolExecutionOutcome outcome = run(tool, "{\"service\":\"" + service + "\"}");
                assertThat(outcome.denialReason()).as(tool + " " + service)
                        .isEqualTo(DenialReason.RESOURCE_NOT_ALLOWED);
            }
        }

        // Only the requests of this test: another test deliberately asks the proxy about the intruder directly.
        String requestsOfThisTest = proxyRequestLog().substring(logBefore);
        assertThat(requestsOfThisTest).contains("GET /v1.44/containers/" + TARGET_NAME + "/json")
                .doesNotContain(INTRUDER_NAME).doesNotContain(intruderId.substring(0, 12));
        assertThat(list.output()).doesNotContain(INTRUDER_NAME, INTRUDER_SECRET);
    }

    /**
     * Risk A of the slice 3 design, recorded as a test: the proxy filters operations, not containers. It serves
     * the intruder's inspect, environment included. The allowlist is the backend's guarantee against the
     * agent, not a barrier against a compromised backend (docs/06-threat-model.md).
     */
    @Test
    void theProxyItselfDoesNotIsolateContainers() throws Exception {
        HttpResponse<String> inspect = rawProxy("GET", "/v1.44/containers/" + INTRUDER_NAME + "/json");

        assertThat(inspect.statusCode()).isEqualTo(200);
        assertThat(inspect.body()).contains(INTRUDER_SECRET);
    }

    // ---- 2. the environment of the target ----------------------------------------------------------------

    @Test
    void theTargetsEnvironmentVariables_neverReachTheOutputOrTheDatabase() {
        ToolExecutionOutcome outcome = run("getContainerStatus", "{\"service\":\"demo-api\"}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        String stored = jdbc.queryForObject("SELECT output::text FROM tool_execution WHERE id = ?", String.class,
                outcome.toolExecutionId());
        for (String leaked : List.of(ENV_SECRET, "DB_PASSWORD", TARGET_NAME)) {
            assertThat(outcome.output()).doesNotContain(leaked);
            assertThat(stored).doesNotContain(leaked);
        }
    }

    /**
     * S9 end to end (docs/06-threat-model.md, section 6): the agent inspects a real container whose environment
     * holds a secret. Two different boundaries are checked: what is stored ({@code tool_execution.output}) and
     * what is sent to the model.
     */
    @Test
    void s9_theAgentInspectsARealContainer_andTheSecretReachesNeitherTheDatabaseNorTheModel() {
        String marker = "[S9] " + uniqueName("env");
        JsonNode execution = ask(operator, conversation(operator, environment.toString()), marker + " inspect it");

        assertThat(execution.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(actions(execution)).singleElement()
                .satisfies(action -> assertThat(action.get("status").asString()).isEqualTo("SUCCEEDED"));
        String stored = jdbc.queryForObject("SELECT output::text FROM tool_execution WHERE agent_execution_id = ?",
                String.class, id(execution));
        assertThat(stored).contains("RUNNING").doesNotContain(ENV_SECRET, "DB_PASSWORD", TARGET_NAME);

        List<LlmRequest> sentToModel = llm.requestsFor(marker);
        assertThat(sentToModel).hasSize(2);
        LlmMessage lastSent = sentToModel.get(1).messages().getLast();
        assertThat(lastSent).isInstanceOfSatisfying(LlmMessage.ToolResult.class,
                result -> assertThat(result.content()).as("the tool result did reach the model").contains("RUNNING"));
        for (LlmRequest request : sentToModel) {
            assertThat(request.toString()).doesNotContain(ENV_SECRET, "DB_PASSWORD", TARGET_NAME);
        }
        assertThat(execution.get("answer").get("text").asString()).doesNotContain(ENV_SECRET);
        assertThat(proxyRequestLog()).contains("GET /v1.44/containers/" + TARGET_NAME + "/json");
    }

    // ---- 3. secrets printed in the target's log ----------------------------------------------------------

    @Test
    void secretsInTheRealLog_areMaskedBeforeAnythingIsStored() {
        ToolExecutionOutcome outcome = run("getContainerLogs", "{\"service\":\"demo-api\",\"tail\":50}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        String stored = jdbc.queryForObject("SELECT output::text FROM tool_execution WHERE id = ?", String.class,
                outcome.toolExecutionId());
        assertThat(stored).contains("target ready", "stderr", "<redacted").doesNotContain(LOG_JWT, LOG_PASSWORD);
        assertThat(outcome.output()).doesNotContain(LOG_JWT, LOG_PASSWORD);
        assertThat(proxyRequestLog()).contains("GET /v1.44/containers/" + TARGET_NAME + "/logs?");
    }

    // ---- the proxy's policy ------------------------------------------------------------------------------

    @Test
    void theProxyRefusesEverythingBeyondReadingContainersAndLogs() throws Exception {
        String target = "/v1.44/containers/" + TARGET_NAME;
        Map<String, String> refused = Map.ofEntries(
                Map.entry("POST /v1.44/containers/create", "{\"Image\":\"alpine:3.22\",\"HostConfig\":{\"Privileged\":true}}"),
                Map.entry("POST " + target + "/exec", "{\"Cmd\":[\"id\"]}"),
                Map.entry("POST " + target + "/start", ""),
                Map.entry("POST " + target + "/restart", ""),
                Map.entry("POST " + target + "/kill", ""),
                Map.entry("DELETE " + target, ""),
                Map.entry("GET " + target + "/archive?path=/etc/passwd", ""),
                Map.entry("GET " + target + "/export", ""),
                Map.entry("GET /v1.44/info", ""),
                Map.entry("GET /v1.44/images/json", ""),
                Map.entry("GET /v1.44/volumes", ""),
                Map.entry("GET /v1.44/networks", ""),
                Map.entry("GET /v1.44/events", ""));
        for (Map.Entry<String, String> request : refused.entrySet()) {
            String[] parts = request.getKey().split(" ", 2);
            assertThat(rawProxy(parts[0], parts[1], request.getValue()).statusCode()).as(request.getKey())
                    .isEqualTo(403);
        }
        assertThat(TARGET.isRunning()).isTrue();
    }

    @Test
    void restartIsRefusedByTheProxyInThisSlice_andTheAdapterReportsItAsForbidden() {
        ContainerRef ref = FakeContainerRuntime.ref(UUID.randomUUID(), "demo-api", TARGET_NAME);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.restart(ref, Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(ContainerRuntimeException.class, exception -> assertThat(exception.category())
                        .isEqualTo(ContainerRuntimeException.Category.FORBIDDEN));
    }

    /**
     * Residual risk found while designing slice 3: websocket attach is a GET, so the proxy lets it through and
     * a client could write to the stdin of a container started with an open stdin. The backend never calls
     * it; compose forbids stdin_open (scripts/check-compose-docker-socket.sh). If a proxy upgrade starts
     * blocking it, this test fails and the threat model can be updated.
     */
    @Test
    void websocketAttach_isNotBlockedByTheProxy_residualRisk() throws IOException {
        byte[] nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        String request = "GET /v1.44/containers/" + TARGET_NAME + "/attach/ws?stream=1&stdout=1 HTTP/1.1\r\n"
                + "Host: docker\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: " + java.util.Base64.getEncoder().encodeToString(nonce) + "\r\n\r\n";
        try (Socket socket = new Socket(PROXY.getHost(), PROXY.getMappedPort(2375))) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String statusLine = new String(in.readNBytes(32), StandardCharsets.US_ASCII);
            assertThat(statusLine).startsWith("HTTP/1.1 101");
        }
    }

    // ---- connectivity ------------------------------------------------------------------------------------

    @Test
    void connectivityCheck_reachesTheRealEngine() {
        JsonNode check = read(post("/api/v1/environments/" + environment + "/connectivity-check", admin.token(), null));

        assertThat(check.get("reachable").asBoolean()).isTrue();
        assertThat(check.get("engineVersion").asString()).isNotBlank();
    }

    @Test
    void theProxyHereIsTheOneFromCompose() throws IOException {
        String compose = Files.readString(Path.of("..", "docker-compose.yml"));

        assertThat(compose).contains("image: " + PROXY_IMAGE)
                .contains("ALLOW_RESTARTS: \"0\"", "POST: \"0\"", "EVENTS: \"0\"", "CONTAINERS: \"1\"",
                        "ALLOW_LOGS: \"1\"");
    }

    // ---- slice 5: deterministic findings on real containers ---------------------------------------------

    /**
     * The findings that matter most, produced by the real Docker Engine and read through the proxy: a kernel
     * OOM kill, a plain SIGKILL (the ambiguous 137), an error exit, a SIGTERM stop and a restart loop.
     */
    @Test
    void realContainers_produceTheExpectedFindings() {
        DockerClient docker = DockerClientFactory.instance().client();
        long sixteenMegabytes = 16L * 1024 * 1024;
        Map<String, String> cases = new LinkedHashMap<>();
        try {
            cases.put("oom-case", start(docker, "oom", HostConfig.newHostConfig().withMemory(sixteenMegabytes)
                    .withMemorySwap(sixteenMegabytes), "sh", "-c", "tail /dev/zero"));
            cases.put("sigkill-case", start(docker, "sigkill", HostConfig.newHostConfig(), "sleep", "300"));
            cases.put("error-case", start(docker, "error", HostConfig.newHostConfig(), "sh", "-c", "exit 3"));
            cases.put("sigterm-case", start(docker, "sigterm", HostConfig.newHostConfig(),
                    "sh", "-c", "trap 'exit 143' TERM; sleep 300 & wait"));
            cases.put("loop-case", start(docker, "loop", HostConfig.newHostConfig()
                    .withRestartPolicy(RestartPolicy.onFailureRestart(50)), "sh", "-c", "sleep 1; exit 1"));

            docker.killContainerCmd(cases.get("sigkill-case")).exec();
            await().atMost(Duration.ofSeconds(10)).until(() -> running(docker, cases.get("sigterm-case")));
            docker.stopContainerCmd(cases.get("sigterm-case")).withTimeout(10).exec();
            for (String service : List.of("oom-case", "sigkill-case", "error-case", "sigterm-case")) {
                await().atMost(Duration.ofSeconds(30)).until(() -> !running(docker, cases.get(service)));
            }
            await().atMost(Duration.ofSeconds(60)).until(() -> restartCount(docker, cases.get("loop-case")) >= 3);
            cases.forEach((service, container) -> allowlistService(admin, environment.toString(), service, container));

            assertThat(findingCodes("oom-case")).containsExactly("OOM_KILLED");
            assertThat(findingCodes("sigkill-case")).containsExactly("KILLED_BY_SIGKILL");
            assertThat(findingCodes("error-case")).containsExactly("EXITED_WITH_ERROR");
            assertThat(findingCodes("sigterm-case")).containsExactly("STOPPED");
            assertThat(findingCodes("loop-case")).contains("RESTART_LOOP");
        } finally {
            cases.values().forEach(container -> docker.removeContainerCmd(container).withForce(true).exec());
        }
    }

    /**
     * Slice 6.1, on the real engine through the proxy: Docker keeps the logs of every run, and the tool reads only
     * the current one unless since is given. A stopped container keeps its last health, which is not reported.
     */
    @Test
    void realLogs_defaultToTheCurrentRun_andAStoppedContainersHealthIsNotApplicable() {
        DockerClient docker = DockerClientFactory.instance().client();
        List<String> created = new ArrayList<>();
        try {
            String runs = start(docker, "runs", HostConfig.newHostConfig(),
                    "sh", "-c", "echo run-$(cat /proc/sys/kernel/random/uuid); echo run-done");
            created.add(runs);
            await().atMost(Duration.ofSeconds(10)).until(() -> !running(docker, runs));
            docker.startContainerCmd(runs).exec();
            allowlistService(admin, environment.toString(), "runs-case", runs);
            await().atMost(Duration.ofSeconds(10)).until(() -> data(run("getContainerLogs",
                    "{\"service\":\"runs-case\",\"since\":\"1h\"}")).get("lines").size() == 4);

            List<String> everyRun = texts(data(run("getContainerLogs",
                    "{\"service\":\"runs-case\",\"since\":\"1h\"}")));
            JsonNode current = data(run("getContainerLogs", "{\"service\":\"runs-case\"}"));
            assertThat(current.get("scope").asString()).isEqualTo("CURRENT_RUN");
            assertThat(texts(current)).containsExactlyElementsOf(everyRun.subList(2, 4));
            assertThat(everyRun.get(0)).as("each run prints its own marker").isNotEqualTo(everyRun.get(2));

            String sick = docker.createContainerCmd(TARGET_IMAGE)
                    .withName("devops-it-sick-" + SUFFIX)
                    .withHealthcheck(new HealthCheck().withTest(List.of("CMD", "false"))
                            .withInterval(Duration.ofSeconds(1).toNanos()).withRetries(1))
                    .withCmd("sh", "-c", "trap 'exit 143' TERM; sleep 300 & wait")
                    .exec().getId();
            created.add(sick);
            docker.startContainerCmd(sick).exec();
            allowlistService(admin, environment.toString(), "sick-case", sick);
            await().atMost(Duration.ofSeconds(30)).until(() -> "UNHEALTHY".equals(
                    data(run("getContainerStatus", "{\"service\":\"sick-case\"}")).get("health").asString()));
            docker.stopContainerCmd(sick).withTimeout(10).exec();

            assertThat(docker.inspectContainerCmd(sick).exec().getState().getHealth().getStatus())
                    .as("Docker keeps the last health").isEqualTo("unhealthy");
            JsonNode stopped = data(run("getContainerStatus", "{\"service\":\"sick-case\"}"));
            assertThat(stopped.get("state").asString()).isEqualTo("EXITED");
            assertThat(stopped.get("health").asString()).isEqualTo("NOT_APPLICABLE");
        } finally {
            created.forEach(container -> docker.removeContainerCmd(container).withForce(true).exec());
        }
    }

    private static List<String> texts(JsonNode logs) {
        return logs.get("lines").valueStream().map(line -> line.get("text").asString()).toList();
    }

    private List<String> findingCodes(String service) {
        ToolExecutionOutcome outcome = run("getContainerStatus", "{\"service\":\"" + service + "\"}");
        assertThat(outcome.status()).as(service).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        return json.readTree(outcome.output()).get("findings").valueStream()
                .map(finding -> finding.get("code").asString()).toList();
    }

    private static String start(DockerClient docker, String kind, HostConfig hostConfig, String... command) {
        String id = docker.createContainerCmd(TARGET_IMAGE)
                .withName("devops-it-" + kind + "-" + SUFFIX)
                .withHostConfig(hostConfig)
                .withCmd(command)
                .exec().getId();
        docker.startContainerCmd(id).exec();
        return id;
    }

    private static boolean running(DockerClient docker, String id) {
        return Boolean.TRUE.equals(docker.inspectContainerCmd(id).exec().getState().getRunning());
    }

    private static int restartCount(DockerClient docker, String id) {
        Integer count = docker.inspectContainerCmd(id).exec().getRestartCount();
        return count == null ? 0 : count;
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private ToolExecutionOutcome run(String tool, String argumentsJson) {
        ExecutionIds fixture = executionFixture(operator);
        return executor.execute(new ToolExecutionRequest(
                new PolicyContext(operator.organizationId(), environment, operator.id(), 10),
                fixture.agentExecutionId(), fixture.llmCallId(), 1,
                new ToolProposal(tool, argumentsJson, "call-1", "real docker test")));
    }

    private JsonNode data(ToolExecutionOutcome outcome) {
        return json.readTree(outcome.output()).get("data");
    }

    /** The proxy logs every request line (haproxy httplog): what reached Docker, and what did not. */
    private static String proxyRequestLog() {
        return PROXY.getLogs();
    }

    private static HttpResponse<String> rawProxy(String method, String path) throws Exception {
        return rawProxy(method, path, "");
    }

    private static HttpResponse<String> rawProxy(String method, String path, String body) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(proxyUrl() + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .method(method, body.isEmpty()
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
