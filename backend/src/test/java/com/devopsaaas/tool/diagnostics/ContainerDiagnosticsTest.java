package com.devopsaaas.tool.diagnostics;

import static com.devopsaaas.tool.container.ContainerState.EXITED;
import static com.devopsaaas.tool.container.ContainerState.NOT_FOUND;
import static com.devopsaaas.tool.container.ContainerState.RESTARTING;
import static com.devopsaaas.tool.container.ContainerState.RUNNING;
import static com.devopsaaas.tool.container.HealthStatus.HEALTHY;
import static com.devopsaaas.tool.container.HealthStatus.NONE;
import static com.devopsaaas.tool.container.HealthStatus.STARTING;
import static com.devopsaaas.tool.container.HealthStatus.UNHEALTHY;
import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.tool.api.Finding;
import com.devopsaaas.tool.api.FindingSeverity;
import com.devopsaaas.tool.container.ContainerSnapshot;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.HealthStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The rules of docs/05-contratos-das-ferramentas.md, section 8.3, as a table of cases (RF-33). */
class ContainerDiagnosticsTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final Instant LONG_AGO = NOW.minus(Duration.ofHours(2));
    private static final Instant JUST_NOW = NOW.minusSeconds(10);

    private final ContainerDiagnostics diagnostics = new ContainerDiagnostics(
            new DiagnosticsProperties(3, Duration.ofSeconds(60)), Clock.fixed(NOW, ZoneOffset.UTC));

    static Stream<Arguments> cases() {
        return Stream.of(
                // description, state, health, exitCode, oomKilled, restartCount, startedAt, expected codes
                row("running and healthy: nothing to say", RUNNING, HEALTHY, 0, false, 0, LONG_AGO),
                row("running without healthcheck", RUNNING, NONE, 0, false, 0, LONG_AGO, "NO_HEALTHCHECK"),
                row("healthcheck still starting: no verdict yet", RUNNING, STARTING, 0, false, 0, LONG_AGO),
                row("running but unhealthy", RUNNING, UNHEALTHY, 0, false, 0, LONG_AGO, "UNHEALTHY"),
                row("started 10 s ago", RUNNING, HEALTHY, 0, false, 0, JUST_NOW, "RECENTLY_STARTED"),
                row("stopped normally", EXITED, NONE, 0, false, 0, LONG_AGO, "STOPPED"),
                row("stopped by SIGTERM (docker stop of a JVM)", EXITED, NONE, 143, false, 0, LONG_AGO, "STOPPED"),
                row("exited with an error", EXITED, NONE, 42, false, 0, LONG_AGO, "EXITED_WITH_ERROR"),
                row("137 with the OOM flag: OOM, nothing else", EXITED, NONE, 137, true, 0, LONG_AGO, "OOM_KILLED"),
                row("137 without the OOM flag: SIGKILL, not concluded as OOM", EXITED, NONE, 137, false, 0, LONG_AGO,
                        "KILLED_BY_SIGKILL"),
                row("stopped after being unhealthy: the exit, not the stale health", EXITED, UNHEALTHY, 1, false, 0,
                        LONG_AGO, "EXITED_WITH_ERROR"),
                row("stopped without healthcheck: no NO_HEALTHCHECK", EXITED, NONE, 0, false, 0, LONG_AGO, "STOPPED"),
                row("being restarted by the policy", RESTARTING, NONE, 1, false, 1, LONG_AGO, "RESTART_LOOP"),
                row("restarted 3 times: a loop", RUNNING, HEALTHY, 0, false, 3, LONG_AGO, "RESTART_LOOP"),
                row("restarted 2 times: below the threshold", RUNNING, HEALTHY, 0, false, 2, LONG_AGO),
                row("OOM in a restart loop: both", RESTARTING, NONE, 137, true, 5, LONG_AGO, "OOM_KILLED",
                        "RESTART_LOOP"),
                row("allowlisted but missing", NOT_FOUND, NONE, null, false, 0, null, "CONTAINER_NOT_FOUND"),
                row("unhealthy, just started, restarted often", RUNNING, UNHEALTHY, 0, false, 4, JUST_NOW, "UNHEALTHY",
                        "RESTART_LOOP", "RECENTLY_STARTED"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void findings(String description, ContainerState state, HealthStatus health, Integer exitCode, boolean oomKilled,
            int restartCount, Instant startedAt, List<String> expected) {
        ContainerSnapshot snapshot = new ContainerSnapshot("demo-api", state, health, exitCode, oomKilled,
                restartCount, startedAt, null, "demo:1");

        assertThat(diagnostics.diagnose(snapshot)).extracting(Finding::code).containsExactlyElementsOf(expected);
    }

    @Test
    void theAmbiguous137_saysItIsNotConclusive_andShowsTheEvidence() {
        Finding finding = diagnostics.diagnose(new ContainerSnapshot("demo-api", EXITED, NONE, 137, false, 0,
                LONG_AGO, NOW, "demo:1")).getFirst();

        assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(finding.message()).contains("not conclusive").contains("docker kill").contains("docker stop");
        assertThat(finding.evidence()).containsEntry("exitCode", "137").containsEntry("oomKilled", "false")
                .containsEntry("service", "demo-api").containsEntry("state", "EXITED");
    }

    /** Slice 6.1: a stopped container keeps its last health in Docker; the evidence does not repeat it. */
    @Test
    void theEvidence_neverShowsTheStaleHealthOfAStoppedContainer() {
        Finding stopped = diagnostics.diagnose(new ContainerSnapshot("demo-api", EXITED, UNHEALTHY, 137, false, 0,
                LONG_AGO, NOW, "demo:1")).getFirst();
        Finding running = diagnostics.diagnose(new ContainerSnapshot("demo-api", RUNNING, UNHEALTHY, 0, false, 0,
                LONG_AGO, null, "demo:1")).getFirst();

        assertThat(stopped.code()).isEqualTo("KILLED_BY_SIGKILL");
        assertThat(stopped.evidence()).containsEntry("health", "NOT_APPLICABLE");
        assertThat(running.code()).isEqualTo("UNHEALTHY");
        assertThat(running.evidence()).containsEntry("health", "UNHEALTHY");
    }

    /** Slice 6.1: logs default to the current run, so the finding says how to reach the earlier ones. */
    @Test
    void aRestartLoop_pointsToTheLogsOfEarlierRuns() {
        for (ContainerSnapshot snapshot : List.of(
                new ContainerSnapshot("demo-api", RESTARTING, NONE, 1, false, 1, LONG_AGO, NOW, "demo:1"),
                new ContainerSnapshot("demo-api", RUNNING, HEALTHY, 0, false, 5, LONG_AGO, null, "demo:1"))) {
            assertThat(diagnostics.diagnose(snapshot)).filteredOn(finding -> finding.code().equals("RESTART_LOOP"))
                    .singleElement().satisfies(finding -> assertThat(finding.message())
                            .contains("earlier runs").contains("getContainerLogs").contains("since"));
        }
    }

    @Test
    void findingsAreOrderedBySeverity_thenCode() {
        List<Finding> findings = diagnostics.diagnose(new ContainerSnapshot("demo-api", RUNNING, UNHEALTHY, 0,
                false, 4, JUST_NOW, null, "demo:1"));

        assertThat(findings).extracting(Finding::severity)
                .containsExactly(FindingSeverity.HIGH, FindingSeverity.MEDIUM, FindingSeverity.INFO);
    }

    private static Arguments row(String description, ContainerState state, HealthStatus health, Integer exitCode,
            boolean oomKilled, int restartCount, Instant startedAt, String... expected) {
        return Arguments.of(description, state, health, exitCode, oomKilled, restartCount, startedAt,
                List.of(expected));
    }
}
