package com.devopsaaas.integration.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.devopsaaas.tool.container.ContainerLogs;
import com.devopsaaas.tool.container.ContainerRef;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import com.devopsaaas.tool.container.ContainerRuntimeException.Category;
import com.devopsaaas.tool.container.ContainerSnapshot;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import com.devopsaaas.tool.container.HealthStatus;
import com.devopsaaas.tool.container.LogLine;
import com.devopsaaas.tool.container.LogQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The adapter against recorded Docker Engine 29.3.1 responses (captured through the docker-socket-proxy and
 * kept in src/test/resources/docker-api), served by a stub on the JDK's HTTP server.
 */
@ExtendWith(OutputCaptureExtension.class)
class DockerEngineContainerRuntimeTest {

    private static final String API = "/v1.44";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private DockerApiStub stub;
    private DockerEngineContainerRuntime runtime;
    private final ContainerRef demoApi = FakeContainerRuntime.ref(UUID.randomUUID(), "demo-api", "devops-demo-api-1");

    @BeforeEach
    void start() {
        stub = new DockerApiStub();
        runtime = runtime(Duration.ofSeconds(2), 1024 * 1024);
    }

    @AfterEach
    void stop() {
        runtime.close();
        stub.close();
    }

    private DockerEngineContainerRuntime runtime(Duration readTimeout, int maxResponseBytes) {
        RuntimeConnectionProperties properties = new RuntimeConnectionProperties(
                Map.of("local", new RuntimeConnectionProperties.Connection(stub.url())),
                new RuntimeConnectionProperties.Docker("1.44", Duration.ofSeconds(1), readTimeout, maxResponseBytes,
                        40));
        return new DockerEngineContainerRuntime(properties, meters);
    }

    // ---- inspect ----------------------------------------------------------------------------------------

    @Test
    void inspect_mapsOnlyDomainFields_andTheEnvironmentNeverLeavesTheAdapter() {
        String recorded = fixture("inspect-running-healthy.json");
        assertThat(recorded).contains("DB_PASSWORD=fixture-db-password-not-real");
        stub.json("GET", API + "/containers/devops-demo-api-1/json", 200, recorded);

        ContainerSnapshot snapshot = runtime.inspect(demoApi);

        assertThat(snapshot.serviceName()).isEqualTo("demo-api");
        assertThat(snapshot.state()).isEqualTo(ContainerState.RUNNING);
        assertThat(snapshot.health()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(snapshot.exitCode()).isZero();
        assertThat(snapshot.oomKilled()).isFalse();
        assertThat(snapshot.image()).isEqualTo("curlimages/curl:latest");
        assertThat(snapshot.startedAt()).isNotNull();
        assertThat(snapshot.finishedAt()).as("year 1 means never").isNull();
        assertThat(snapshot.toString()).doesNotContain("fixture-db-password-not-real").doesNotContain("DB_PASSWORD")
                .doesNotContain("devops-demo-api-1");
    }

    @Test
    void theInspectModel_hasNoFieldForEnvironmentCommandOrMounts() {
        List<String> declared = Arrays.stream(DockerInspect.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        List<String> config = Arrays.stream(DockerInspect.Config.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(declared).containsExactlyInAnyOrder("restartCount", "state", "config");
        assertThat(config).containsExactly("image");
    }

    @Test
    void inspect_ofAnExitedContainer_reportsExitCodeAndTimes() {
        stub.json("GET", API + "/containers/devops-demo-api-1/json", 200, fixture("inspect-exited.json"));

        ContainerSnapshot snapshot = runtime.inspect(demoApi);

        assertThat(snapshot.state()).isEqualTo(ContainerState.EXITED);
        assertThat(snapshot.exitCode()).isEqualTo(3);
        assertThat(snapshot.health()).isEqualTo(HealthStatus.NONE);
        assertThat(snapshot.finishedAt()).isAfter(snapshot.startedAt());
    }

    @Test
    void everyRequest_isPinnedToTheConfiguredApiVersion_andAddressedByTheAllowlistedName() {
        stub.json("GET", API + "/containers/devops-demo-api-1/json", 200, fixture("inspect-exited.json"));

        runtime.inspect(demoApi);

        assertThat(stub.requests()).containsExactly("GET /v1.44/containers/devops-demo-api-1/json");
    }

    // ---- list -------------------------------------------------------------------------------------------

    @Test
    void list_asksOnlyForTheGivenContainers_andReportsMissingOnesAsNotFound() {
        stub.json("GET", API + "/containers/devops-demo-api-1/json", 200, fixture("inspect-running-healthy.json"));
        ContainerRef gone = FakeContainerRuntime.ref(UUID.randomUUID(), "worker", "devops-worker-1");

        List<ContainerSnapshot> snapshots = runtime.list(List.of(demoApi, gone));

        assertThat(snapshots).extracting(ContainerSnapshot::serviceName, ContainerSnapshot::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("demo-api", ContainerState.RUNNING),
                        org.assertj.core.groups.Tuple.tuple("worker", ContainerState.NOT_FOUND));
        assertThat(stub.requests()).containsExactly(
                "GET /v1.44/containers/devops-demo-api-1/json",
                "GET /v1.44/containers/devops-worker-1/json");
    }

    // ---- logs -------------------------------------------------------------------------------------------

    @Test
    void logs_demultiplexStdoutAndStderrFrames_withTimestamps() {
        stub.respond("GET", API + "/containers/devops-demo-api-1/logs", new DockerApiStub.Response(200,
                "application/vnd.docker.multiplexed-stream", bytes("logs-multiplexed.bin"), 0));

        ContainerLogs logs = runtime.logs(demoApi, new LogQuery(200, null));

        assertThat(logs.serviceName()).isEqualTo("demo-api");
        assertThat(logs.lines()).extracting(LogLine::stream, LogLine::text).containsExactly(
                org.assertj.core.groups.Tuple.tuple("stderr", "warning: disk at 91%"),
                org.assertj.core.groups.Tuple.tuple("stdout", "started demo"),
                org.assertj.core.groups.Tuple.tuple("stdout", "request ok"));
        assertThat(logs.lines()).allSatisfy(line -> assertThat(line.timestamp()).isNotNull());
        assertThat(logs.truncated()).isFalse();
        assertThat(stub.requests()).singleElement().asString()
                .startsWith("GET /v1.44/containers/devops-demo-api-1/logs?")
                .contains("stdout=1", "stderr=1", "timestamps=1", "tail=200").doesNotContain("since");
    }

    @Test
    void logs_ofATtyContainer_areReadAsRawText() {
        stub.respond("GET", API + "/containers/devops-demo-api-1/logs", new DockerApiStub.Response(200,
                "application/vnd.docker.raw-stream", bytes("logs-tty.bin"), 0));

        ContainerLogs logs = runtime.logs(demoApi, new LogQuery(50, null));

        assertThat(logs.lines()).extracting(LogLine::text).containsExactly("tty line one", "tty line two");
    }

    @Test
    void logs_sinceIsSentAsUnixTime_withTheNanoseconds() {
        stub.respond("GET", API + "/containers/devops-demo-api-1/logs", new DockerApiStub.Response(200,
                "application/vnd.docker.multiplexed-stream", new byte[0], 0));

        runtime.logs(demoApi, new LogQuery(10, Instant.parse("2026-09-28T02:54:59.231776520Z")));
        runtime.logs(demoApi, new LogQuery(10, Instant.parse("2026-09-28T02:54:59Z")));

        // Whole seconds would let a restart within the same second leak the previous run's lines.
        assertThat(stub.requests().get(0)).contains("since=1790564099.231776520");
        assertThat(stub.requests().get(1)).contains("since=1790564099.000000000");
    }

    @Test
    void logs_areCappedInBytes_andLongLinesAreCut() {
        String longLine = "x".repeat(100);
        byte[] body = ("2026-09-26T23:58:46.077630866Z " + longLine + "\n").repeat(10).getBytes(StandardCharsets.UTF_8);
        stub.respond("GET", API + "/containers/devops-demo-api-1/logs",
                new DockerApiStub.Response(200, "application/vnd.docker.raw-stream", body, 0));
        runtime.close();
        runtime = runtime(Duration.ofSeconds(2), 300);

        ContainerLogs logs = runtime.logs(demoApi, new LogQuery(500, null));

        assertThat(logs.truncated()).isTrue();
        assertThat(logs.lines()).hasSizeLessThanOrEqualTo(3)
                .allSatisfy(line -> assertThat(line.text()).hasSizeLessThanOrEqualTo(40 + 20));
        assertThat(logs.lines().getFirst().text()).endsWith(DockerLogDecoder.TRUNCATION_MARK);
    }

    // ---- errors -----------------------------------------------------------------------------------------

    @Test
    void httpErrors_becomeCategories_withoutDockerMessages() {
        stub.json("GET", API + "/containers/devops-demo-api-1/json", 403, "Forbidden by proxy: devops-demo-api-1");
        assertCategory(() -> runtime.inspect(demoApi), Category.FORBIDDEN);

        stub.json("GET", API + "/containers/devops-demo-api-1/json", 500, "{\"message\":\"daemon exploded\"}");
        assertCategory(() -> runtime.inspect(demoApi), Category.UNAVAILABLE);

        stub.json("GET", API + "/containers/devops-demo-api-1/json", 400,
                "{\"message\":\"client version 1.44 is too old\"}");
        assertCategory(() -> runtime.inspect(demoApi), Category.UNEXPECTED);

        stub.json("GET", API + "/containers/devops-demo-api-1/json", 200, "not json");
        assertCategory(() -> runtime.inspect(demoApi), Category.UNEXPECTED);
    }

    /**
     * Slice 9a: an inspect body the adapter cannot parse is logged as a warning. The container's environment is
     * not mapped, so even right next to the parse error neither the log nor any message in the exception chain
     * carries it (Jackson's INCLUDE_SOURCE_IN_LOCATION is off).
     */
    @Test
    void anUnparsableInspect_neverLogsNorThrowsTheContainersEnvironment(CapturedOutput output) {
        String secret = "canary-env-secret-in-a-broken-inspect";
        for (String body : List.of(
                "{\"Config\":{\"Env\":[\"DB_PASSWORD=" + secret + "\"],\"Image\":\"x\"},\"State\":{\"Status\": oops",
                "{\"Config\":{\"Env\":[\"DB_PASSWORD=" + secret + "\"],\"Image\":\"x\"},\"State\":\"broken\"}",
                "{\"Config\":{\"Env\":\"DB_PASSWORD=" + secret + "\",\"Image\":[1]},\"RestartCount\":\"x\"}")) {
            assertNothingLeaks(body, secret, output);
        }
    }

    /**
     * Slice 9a, finding 9a-02 (closed): unexpected external data never goes into a log. A value in a MAPPED field
     * with the wrong type used to be quoted by Jackson's message, which reached the warning log and the exception
     * chain. Now only the exception types, the field path and the position are kept.
     */
    @Test
    void anUnexpectedValueInAMappedField_isNeverLogged_onlyItsTypeAndPath(CapturedOutput output) {
        String secret = "canary-value-in-a-mapped-field";
        assertNothingLeaks("{\"State\":\"" + secret + "\",\"Config\":{\"Image\":\"x\"}}", secret, output);
        assertNothingLeaks("{\"Config\":{\"Image\":[\"" + secret + "\"]}}", secret, output);
        assertNothingLeaks("{\"State\":{\"Health\":\"" + secret + "\"}}", secret, output);
        assertNothingLeaks("{\"State\":{\"ExitCode\":\"" + secret + "\"}}", secret, output);

        assertThat(output.getAll()).contains("Unexpected Docker API response for operation inspect")
                .contains("at 'State'").contains("at 'Config.Image'").contains("at 'State.Health'")
                .contains("at 'State.ExitCode'").contains("line 1, column");
    }

    private void assertNothingLeaks(String body, String secret, CapturedOutput output) {
        stub.json("GET", API + "/containers/devops-demo-api-1/json", 200, body);
        Throwable thrown = catchThrowable(() -> runtime.inspect(demoApi));
        assertThat(thrown).isInstanceOf(ContainerRuntimeException.class);
        assertThat(((ContainerRuntimeException) thrown).category()).isEqualTo(Category.UNEXPECTED);
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage())).doesNotContain(secret);
        }
        assertThat(output.getAll()).contains("Unexpected Docker API response").doesNotContain(secret);
    }

    @Test
    void missingContainer_isNotFound() {
        assertCategory(() -> runtime.inspect(demoApi), Category.NOT_FOUND);
        assertCategory(() -> runtime.logs(demoApi, new LogQuery(10, null)), Category.NOT_FOUND);
    }

    @Test
    void slowResponse_endsAsUnavailable_withinTheReadTimeout() {
        stub.respond("GET", API + "/containers/devops-demo-api-1/json", new DockerApiStub.Response(200,
                "application/json", fixture("inspect-exited.json").getBytes(StandardCharsets.UTF_8), 3_000));
        runtime.close();
        runtime = runtime(Duration.ofMillis(300), 1024 * 1024);

        long start = System.nanoTime();
        assertCategory(() -> runtime.inspect(demoApi), Category.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void unreachableProxy_isUnavailable() {
        stub.close();

        assertCategory(() -> runtime.inspect(demoApi), Category.UNAVAILABLE);
    }

    @Test
    void unknownConnection_isUnavailable_andNothingIsCalled() {
        ContainerRef elsewhere = FakeContainerRuntime.ref(UUID.randomUUID(), "demo-api", "devops-demo-api-1",
                "production");

        assertCategory(() -> runtime.inspect(elsewhere), Category.UNAVAILABLE);
        assertCategory(() -> runtime.version("production"), Category.UNAVAILABLE);
        assertThat(stub.requests()).isEmpty();
    }

    // ---- restart and version ----------------------------------------------------------------------------

    @Test
    void restart_postsWithTheGracefulStopTimeout_andAProxyRefusalIsForbidden() {
        stub.respond("POST", API + "/containers/devops-demo-api-1/restart",
                new DockerApiStub.Response(204, null, new byte[0], 0));
        runtime.restart(demoApi, Duration.ofSeconds(10));
        assertThat(stub.requests()).containsExactly("POST /v1.44/containers/devops-demo-api-1/restart?t=10");

        stub.json("POST", API + "/containers/devops-demo-api-1/restart", 403, "Forbidden");
        assertCategory(() -> runtime.restart(demoApi, Duration.ofSeconds(10)), Category.FORBIDDEN);
    }

    @Test
    void version_reportsTheEngine() {
        stub.json("GET", API + "/version", 200, fixture("version.json"));

        assertThat(runtime.version("local").engineVersion()).isEqualTo("29.3.1");
        assertThat(runtime.version("local").apiVersion()).isEqualTo("1.54");
    }

    @Test
    void everyCall_isTimedByOperationAndOutcome() {
        runtime.list(List.of(demoApi));

        assertThat(meters.get("devops.runtime.calls").tag("operation", "inspect").tag("outcome", "NOT_FOUND")
                .timer().count()).isEqualTo(1);
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static void assertCategory(Runnable call, Category category) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ContainerRuntimeException.class, exception -> {
            assertThat(exception.category()).isEqualTo(category);
            assertThat(exception.getMessage()).doesNotContain("devops-demo-api-1").doesNotContain("exploded");
        });
    }

    static byte[] bytes(String name) {
        try (InputStream in = DockerEngineContainerRuntimeTest.class.getResourceAsStream("/docker-api/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String fixture(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }
}
