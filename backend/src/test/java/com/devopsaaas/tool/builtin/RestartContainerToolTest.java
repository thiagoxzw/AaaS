package com.devopsaaas.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.tool.api.Finding;
import com.devopsaaas.tool.api.FindingSeverity;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.api.ToolExecutionContext;
import com.devopsaaas.tool.api.ToolResult;
import com.devopsaaas.tool.builtin.RestartContainerTool.Verification;
import com.devopsaaas.tool.container.ContainerRef;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import com.devopsaaas.tool.container.ContainerSnapshot;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import com.devopsaaas.tool.container.FakeContainerRuntime.AfterRestart;
import com.devopsaaas.tool.container.HealthStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The verification of restartContainer (docs/05 8.5, slice 8) against the fake runtime, without Spring. The
 * two questions stay apart: the result is a success once the runtime took the restart, and whether the
 * service came back is the verification (a bad one is a HIGH finding, never a failure).
 */
class RestartContainerToolTest {

    private final FakeContainerRuntime runtime = new FakeContainerRuntime();
    private final RestartContainerTool tool = new RestartContainerTool(runtime, new RestartProperties(
            Duration.ofSeconds(10), Duration.ofMillis(600), Duration.ofMillis(10), Duration.ofMillis(100)));

    @Test
    void aContainerThatComesBackHealthy_isVerifiedHealthy() {
        ContainerRef ref = running("healthy", HealthStatus.HEALTHY);

        RestartContainerTool.Output output = output(run(ref));

        assertThat(output.verification()).isEqualTo(Verification.HEALTHY);
        assertThat(output.restartObserved()).isTrue();
        assertThat(output.stateBefore()).isEqualTo(ContainerState.RUNNING);
        assertThat(output.restartedAt()).isNotNull();
        assertThat(findings(run(ref))).isEmpty();
    }

    @Test
    void withoutAHealthcheck_theContainerMustStayRunningForTheStableTime() {
        ContainerRef ref = running("no-healthcheck", HealthStatus.NONE);
        runtime.afterRestart(ref.containerName(), AfterRestart.NO_HEALTHCHECK);

        long start = System.nanoTime();
        RestartContainerTool.Output output = output(run(ref));

        assertThat(output.verification()).isEqualTo(Verification.RUNNING_NO_HEALTHCHECK);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(100));
    }

    /** Restarted, but not healthy: a SUCCESS with a HIGH finding, never a failed call. */
    @Test
    void anUnhealthyContainer_isASuccessWithAHighFinding() {
        ContainerRef ref = running("unhealthy", HealthStatus.HEALTHY);
        runtime.afterRestart(ref.containerName(), AfterRestart.UNHEALTHY);

        ToolResult result = run(ref);

        assertThat(result).isInstanceOf(ToolResult.Success.class);
        assertThat(output(result).verification()).isEqualTo(Verification.UNHEALTHY);
        Finding finding = findings(result).getFirst();
        assertThat(finding.code()).isEqualTo("RESTART_UNVERIFIED");
        assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(finding.evidence()).containsEntry("verification", "UNHEALTHY").containsEntry("healthAfter",
                "UNHEALTHY");
    }

    @Test
    void aContainerThatExitsAgain_isNotRunning() {
        ContainerRef ref = stopped("exits");
        runtime.afterRestart(ref.containerName(), AfterRestart.EXITS);

        RestartContainerTool.Output output = output(run(ref));

        assertThat(output.stateBefore()).isEqualTo(ContainerState.EXITED);
        assertThat(output.verification()).isEqualTo(Verification.NOT_RUNNING);
        assertThat(output.healthAfter()).isEqualTo(HealthStatus.NOT_APPLICABLE);
    }

    @Test
    void aContainerThatNeverSettles_timesOutTheVerification() {
        ContainerRef ref = running("starting", HealthStatus.HEALTHY);
        runtime.afterRestart(ref.containerName(), AfterRestart.STAYS_STARTING);

        RestartContainerTool.Output output = output(run(ref));

        assertThat(output.verification()).isEqualTo(Verification.VERIFICATION_TIMEOUT);
        assertThat(output.restartObserved()).isTrue();
    }

    /** The runtime answering the call is not proof of a restart: StartedAt must move. */
    @Test
    void whenStartedAtDoesNotMove_theRestartIsNotCountedAsDone() {
        ContainerRef ref = running("no-new-start", HealthStatus.HEALTHY);
        runtime.afterRestart(ref.containerName(), AfterRestart.NO_NEW_START);

        ToolResult result = run(ref);
        RestartContainerTool.Output output = output(result);

        assertThat(output.restartObserved()).isFalse();
        assertThat(output.restartedAt()).isNull();
        assertThat(output.verification()).isEqualTo(Verification.VERIFICATION_TIMEOUT);
        assertThat(output.stateAfter()).isEqualTo(ContainerState.RUNNING);
        assertThat(findings(result).getFirst().message()).contains("no new start");
        assertThat(findings(result).getFirst().evidence()).containsEntry("restartObserved", "false");
    }

    /** Nothing was sent: a plain failure, because we know the restart did not happen. */
    @Test
    void anUnreachableRuntimeBeforeTheRestart_isAFailure_andNothingIsSent() {
        ContainerRef ref = running("down-before", HealthStatus.HEALTHY);
        runtime.failWith(ref.containerName(), ContainerRuntimeException.Category.UNAVAILABLE);

        ToolResult result = run(ref);

        assertThat(result).isInstanceOfSatisfying(ToolResult.Failure.class,
                failure -> assertThat(failure.code()).isEqualTo(ToolErrorCode.RUNTIME_UNAVAILABLE));
        assertThat(runtime.calls()).doesNotContain("restart:" + ref.containerName());
    }

    /**
     * The request may have reached the runtime: the tool does not pretend to know. The exception reaches the
     * executor, which records OUTCOME_UNKNOWN and never retries (RestartContainerIT proves that part).
     */
    @Test
    void aFailureOfTheRestartCallItself_isNotTurnedIntoAnAnswer() {
        ContainerRef ref = running("drops", HealthStatus.HEALTHY);
        runtime.failRestartWith(ref.containerName(), ContainerRuntimeException.Category.UNAVAILABLE);

        assertThatThrownBy(() -> run(ref)).isInstanceOf(ContainerRuntimeException.class);
        assertThat(runtime.calls().stream().filter(call -> call.equals("restart:" + ref.containerName())))
                .hasSize(1);
    }

    @Test
    void theDefinition_isHighRisk_neverRetried_andCarriesItsJustificationAsAnArgument() {
        assertThat(tool.definition().riskLevel().name()).isEqualTo("HIGH_RISK");
        assertThat(tool.definition().retryable()).isFalse();
        assertThat(tool.definition().timeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(tool.definition().justificationParameter()).isEqualTo("reason");
        assertThat(tool.definition().impactDescription()).startsWith("Restarting stops the container");
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private ContainerRef running(String name, HealthStatus health) {
        ContainerRef ref = FakeContainerRuntime.ref(UUID.randomUUID(), "demo-api", name + "-" + UUID.randomUUID());
        runtime.setSnapshot(ref.containerName(), new ContainerSnapshot(ref.containerName(), ContainerState.RUNNING,
                health, null, false, 0, Instant.now().minusSeconds(3600), null, "demo:1"));
        return ref;
    }

    private ContainerRef stopped(String name) {
        ContainerRef ref = FakeContainerRuntime.ref(UUID.randomUUID(), "demo-api", name + "-" + UUID.randomUUID());
        Instant started = Instant.now().minusSeconds(3600);
        runtime.setSnapshot(ref.containerName(), new ContainerSnapshot(ref.containerName(), ContainerState.EXITED,
                HealthStatus.UNHEALTHY, 42, false, 0, started, started.plusSeconds(10), "demo:1"));
        return ref;
    }

    private ToolResult run(ContainerRef ref) {
        return tool.execute(new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Optional.of(ref), Instant.now().plusSeconds(90), null),
                new RestartContainerTool.Input("demo-api", "The container is unhealthy; restart it."));
    }

    private static RestartContainerTool.Output output(ToolResult result) {
        return (RestartContainerTool.Output) ((ToolResult.Success) result).data();
    }

    private static List<Finding> findings(ToolResult result) {
        return ((ToolResult.Success) result).findings();
    }
}
