package com.devopsaaas.tool.builtin;

import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.tool.api.Description;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.Tool;
import com.devopsaaas.tool.api.ToolCategory;
import com.devopsaaas.tool.api.ToolDefinition;
import com.devopsaaas.tool.api.ToolExecutionContext;
import com.devopsaaas.tool.api.ToolInput;
import com.devopsaaas.tool.api.ToolResult;
import com.devopsaaas.tool.container.ContainerRef;
import com.devopsaaas.tool.container.ContainerRuntime;
import com.devopsaaas.tool.container.ContainerSnapshot;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.HealthStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Detailed state of one allowlisted service (docs/05-contratos-das-ferramentas.md, 8.3). Only domain fields:
 * the adapter never reads environment variables, command or mounts. Deterministic findings arrive in slice 5.
 */
@Component
public class GetContainerStatusTool implements Tool<GetContainerStatusTool.Input> {

    public record Input(
            @Description(ServiceNames.DESCRIPTION)
            @NotBlank @Pattern(regexp = ServiceNames.PATTERN) String service) implements ToolInput {
    }

    public record Output(String service, ContainerState state, HealthStatus health, Integer exitCode,
            boolean oomKilled, int restartCount, Instant startedAt, Instant finishedAt, String image) {
    }

    private static final ToolDefinition DEFINITION = ToolDefinition.builder("getContainerStatus")
            .description("Get detailed status of one service: state, health, exit code, OOM flag, restart count, "
                    + "timestamps and image.")
            .category(ToolCategory.CONTAINER)
            .riskLevel(RiskLevel.READ_ONLY)
            .requiredPermission(Permission.AGENT_INTERACT)
            .targetParameter("service")
            .timeout(Duration.ofSeconds(10))
            .retryable(true)
            .build();

    private final ContainerRuntime runtime;

    GetContainerStatusTool(ContainerRuntime runtime) {
        this.runtime = runtime;
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
        // The policy resolved the target against the allowlist before this call; there is no other way in.
        ContainerRef target = context.target().orElseThrow(
                () -> new IllegalStateException("getContainerStatus runs only with a resolved target"));
        ContainerSnapshot snapshot = runtime.inspect(target);
        return ToolResult.success(new Output(target.serviceName(), snapshot.state(), snapshot.health(),
                snapshot.exitCode(), snapshot.oomKilled(), snapshot.restartCount(), snapshot.startedAt(),
                snapshot.finishedAt(), snapshot.image()));
    }
}
