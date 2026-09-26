package com.devopsaaas.tool.policy;

import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.environment.EnvironmentDirectory.ActiveEnvironment;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.tool.registry.RegisteredTool;
import com.devopsaaas.tool.registry.ToolRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Capability filter: the registry holds the complete catalog, and this view keeps only what the caller may
 * use in the given environment, with the same rules the policy engine enforces.
 */
@Service
public class ToolCatalog {

    private final ToolRegistry registry;
    private final EnvironmentDirectory environments;

    ToolCatalog(ToolRegistry registry, EnvironmentDirectory environments) {
        this.registry = registry;
        this.environments = environments;
    }

    public record Entry(RegisteredTool tool, boolean requiresApproval) {
    }

    public List<Entry> visibleTo(CurrentUser user, UUID environmentId) {
        ActiveEnvironment environment = environments.findActive(user.organizationId(), environmentId)
                .orElseThrow(() -> ApiException.notFound("Environment not found."));
        return registry.all().stream()
                .filter(tool -> user.has(tool.definition().requiredPermission()))
                .filter(tool -> PolicyRules.permitsAutonomy(tool.definition().riskLevel(),
                        environment.autonomyLevel()))
                .map(tool -> new Entry(tool, PolicyRules.requiresApproval(tool.definition(),
                        environment.autonomyLevel())))
                .toList();
    }
}
