package com.devopsaaas.tool.builtin;

import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.tool.api.Finding;
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
import com.devopsaaas.tool.container.TargetResolver;
import com.devopsaaas.tool.container.TargetResolver.AllowlistedTarget;
import com.devopsaaas.tool.diagnostics.ContainerDiagnostics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Services of the current environment with their state (docs/05-contratos-das-ferramentas.md, 8.2). The
 * runtime is asked about the allowlisted containers only, by exact name: a container outside the allowlist
 * can never appear here, even if it runs on the same host. Findings are listed once, at the top, each with the
 * logical service name in its evidence; a service whose container does not exist is CONTAINER_NOT_FOUND.
 */
@Component
public class ListContainersTool implements Tool<ListContainersTool.Input> {

    public record Input() implements ToolInput {
    }

    public record ServiceView(String service, String description, ContainerState state, HealthStatus health,
            int restartCount) {
    }

    public record Output(List<ServiceView> services) {

        public Output {
            services = List.copyOf(services);
        }
    }

    private static final ToolDefinition DEFINITION = ToolDefinition.builder("listContainers")
            .description("List the services registered in the current environment with their container state and "
                    + "health. Only allowlisted services are visible.")
            .category(ToolCategory.CONTAINER)
            .riskLevel(RiskLevel.READ_ONLY)
            .requiredPermission(Permission.AGENT_INTERACT)
            .timeout(Duration.ofSeconds(10))
            .retryable(true)
            .build();

    private final ContainerRuntime runtime;
    private final TargetResolver targets;
    private final ContainerDiagnostics diagnostics;

    ListContainersTool(ContainerRuntime runtime, TargetResolver targets, ContainerDiagnostics diagnostics) {
        this.runtime = runtime;
        this.targets = targets;
        this.diagnostics = diagnostics;
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
        List<AllowlistedTarget> allowlist = targets.resolveAll(context.organizationId(), context.environmentId());
        if (allowlist.isEmpty()) {
            return ToolResult.success(new Output(List.of()));
        }
        List<ContainerRef> refs = allowlist.stream().map(AllowlistedTarget::ref).toList();
        List<ContainerSnapshot> snapshots = runtime.list(refs);
        List<ServiceView> services = new ArrayList<>(allowlist.size());
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < allowlist.size(); i++) {
            ContainerSnapshot snapshot = snapshots.get(i);
            services.add(new ServiceView(allowlist.get(i).ref().serviceName(), allowlist.get(i).description(),
                    snapshot.state(), snapshot.health(), snapshot.restartCount()));
            findings.addAll(diagnostics.diagnose(snapshot));
        }
        return ToolResult.success(new Output(services), findings);
    }
}
