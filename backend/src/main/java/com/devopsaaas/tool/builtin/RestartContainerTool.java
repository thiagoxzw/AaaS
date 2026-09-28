package com.devopsaaas.tool.builtin;

import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.tool.api.Description;
import com.devopsaaas.tool.api.Finding;
import com.devopsaaas.tool.api.FindingSeverity;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.Tool;
import com.devopsaaas.tool.api.ToolCategory;
import com.devopsaaas.tool.api.ToolDefinition;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.api.ToolExecutionContext;
import com.devopsaaas.tool.api.ToolInput;
import com.devopsaaas.tool.api.ToolResult;
import com.devopsaaas.tool.container.ContainerRef;
import com.devopsaaas.tool.container.ContainerRuntime;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import com.devopsaaas.tool.container.ContainerSnapshot;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.HealthStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Restarts one allowlisted service and verifies, deterministically, whether it came back
 * (docs/05-contratos-das-ferramentas.md, 8.5; slice 8). HIGH_RISK: it always waits for a human approval, and
 * the executor never retries it.
 *
 * <p>Two questions stay apart. "Did the runtime take the restart?" decides the call's status: SUCCEEDED once
 * the restart was accepted; OUTCOME_UNKNOWN (set by the executor) when the call may or may not have reached
 * the runtime; FAILED only when we know nothing was sent. "Did the service come back?" is the
 * {@code verification}, and a bad one is a HIGH finding, never a failed call.
 */
@Component
@EnableConfigurationProperties(RestartProperties.class)
public class RestartContainerTool implements Tool<RestartContainerTool.Input> {

    static final String IMPACT = "Restarting stops the container and starts it again. In-flight requests will fail "
            + "and the service will be unavailable for a few seconds or more.";
    static final String UNVERIFIED = "RESTART_UNVERIFIED";

    public record Input(
            @Description(ServiceNames.DESCRIPTION)
            @NotBlank @Pattern(regexp = ServiceNames.PATTERN) String service,
            @Description("Why the restart is needed, citing the evidence; shown to the human who approves it")
            @NotBlank @Size(min = 10, max = 500) String reason) implements ToolInput {
    }

    /** What the verification saw once the runtime accepted the restart. */
    public enum Verification {
        HEALTHY, RUNNING_NO_HEALTHCHECK, UNHEALTHY, NOT_RUNNING, VERIFICATION_TIMEOUT
    }

    /**
     * @param restartObserved whether a new start of the container was seen (its StartedAt changed): the
     *        runtime answering the restart call is not taken as proof that it restarted
     */
    public record Output(String service, ContainerState stateBefore, ContainerState stateAfter,
            HealthStatus healthAfter, Instant restartedAt, boolean restartObserved, Verification verification) {
    }

    private static final ToolDefinition DEFINITION = ToolDefinition.builder("restartContainer")
            .description("Restart one service's container. Interrupts in-flight requests. Requires human approval. "
                    + "Waits for the container to come back and reports the verified state.")
            .category(ToolCategory.CONTAINER)
            .riskLevel(RiskLevel.HIGH_RISK)
            .requiredPermission(Permission.TOOL_OPERATE)
            .targetParameter("service")
            .justificationParameter("reason")
            .impactDescription(IMPACT)
            .timeout(Duration.ofSeconds(90))
            .retryable(false)
            .build();

    private static final Duration DEADLINE_MARGIN = Duration.ofSeconds(2);

    private final ContainerRuntime runtime;
    private final RestartProperties properties;

    RestartContainerTool(ContainerRuntime runtime, RestartProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Input input) {
        ContainerRef target = context.target().orElseThrow(
                () -> new IllegalStateException("restartContainer runs only with a resolved target"));
        ContainerSnapshot before;
        try {
            before = runtime.inspect(target);
        } catch (ContainerRuntimeException exception) {
            if (exception.category() != ContainerRuntimeException.Category.UNAVAILABLE) {
                throw exception;
            }
            // Nothing was sent yet: this one is a plain failure, not an unknown outcome.
            return ToolResult.failure(ToolErrorCode.RUNTIME_UNAVAILABLE,
                    "The container runtime is unavailable; the restart was not sent.", false);
        }
        // From here on an exception may mean the restart reached the runtime: the executor records it as
        // OUTCOME_UNKNOWN and never retries.
        runtime.restart(target, properties.gracefulStop());
        return verify(context, target, before);
    }

    private ToolResult verify(ToolExecutionContext context, ContainerRef target, ContainerSnapshot before) {
        Instant windowEnd = earliest(Instant.now().plus(properties.verificationWindow()),
                context.deadline().minus(DEADLINE_MARGIN));
        ContainerSnapshot last = null;
        Instant runningSince = null;
        Verification verification = null;
        while (verification == null) {
            ContainerSnapshot now = inspectQuietly(target);
            if (now != null) {
                last = now;
                boolean observed = restarted(before, now);
                runningSince = observed && now.state() == ContainerState.RUNNING
                        ? (runningSince == null ? Instant.now() : runningSince) : null;
                verification = observed ? settled(now, runningSince) : null;
            }
            if (verification == null && !Instant.now().isBefore(windowEnd)) {
                verification = Verification.VERIFICATION_TIMEOUT;
            } else if (verification == null && !pause()) {
                verification = Verification.VERIFICATION_TIMEOUT;
            }
        }
        boolean observed = last != null && restarted(before, last);
        Output output = new Output(target.serviceName(), before.state(), last == null ? null : last.state(),
                last == null ? null : HealthStatus.reported(last.state(), last.health()),
                observed ? last.startedAt() : null, observed, verification);
        return ToolResult.success(output, findings(output));
    }

    /** Null while the container is still on its way (STARTING, RESTARTING, not yet stable). */
    private Verification settled(ContainerSnapshot now, Instant runningSince) {
        return switch (now.state()) {
            case RUNNING -> switch (now.health()) {
                case HEALTHY -> Verification.HEALTHY;
                case UNHEALTHY -> Verification.UNHEALTHY;
                case NONE -> Duration.between(runningSince, Instant.now()).compareTo(properties.stableRunning()) >= 0
                        ? Verification.RUNNING_NO_HEALTHCHECK : null;
                default -> null;
            };
            case EXITED, DEAD -> Verification.NOT_RUNNING;
            default -> null;
        };
    }

    /** A new start of the container: its StartedAt moved forward. */
    private static boolean restarted(ContainerSnapshot before, ContainerSnapshot now) {
        return now.startedAt() != null && (before.startedAt() == null || now.startedAt().isAfter(before.startedAt()));
    }

    private ContainerSnapshot inspectQuietly(ContainerRef target) {
        try {
            return runtime.inspect(target);
        } catch (ContainerRuntimeException exception) {
            // The restart was accepted; a failed look is not a failed restart. The window decides.
            return null;
        }
    }

    private boolean pause() {
        try {
            Thread.sleep(properties.pollInterval());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static List<Finding> findings(Output output) {
        if (output.verification() == Verification.HEALTHY
                || output.verification() == Verification.RUNNING_NO_HEALTHCHECK) {
            return List.of();
        }
        String message = switch (output.verification()) {
            case UNHEALTHY -> "The container restarted, but its healthcheck reports it unhealthy.";
            case NOT_RUNNING -> "The container restarted, but it is not running anymore.";
            default -> output.restartObserved()
                    ? "The container restarted, but it did not settle within the verification window."
                    : "The runtime accepted the restart, but no new start of the container was observed "
                            + "within the verification window.";
        };
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("service", output.service());
        evidence.put("verification", output.verification().name());
        evidence.put("restartObserved", Boolean.toString(output.restartObserved()));
        evidence.put("stateAfter", String.valueOf(output.stateAfter()));
        evidence.put("healthAfter", String.valueOf(output.healthAfter()));
        return List.of(new Finding(UNVERIFIED, FindingSeverity.HIGH, message, evidence));
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
