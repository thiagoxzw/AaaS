package com.devopsaaas.tool.policy;

import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.environment.EnvironmentDirectory.ActiveEnvironment;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.tool.registry.RegisteredTool;
import com.devopsaaas.tool.registry.ToolRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        return visibleTo(user.organizationId(), user.permissions(), environmentId)
                .orElseThrow(() -> ApiException.notFound("Environment not found."));
    }

    /**
     * The same filter for the agent, with the requester's permissions as they are now (reloaded for every LLM
     * turn, RNF-SEG-13). Empty when the environment is no longer available.
     */
    public Optional<List<Entry>> visibleTo(UUID organizationId, Set<Permission> permissions, UUID environmentId) {
        return environments.findActive(organizationId, environmentId)
                .map(environment -> entries(permissions, environment));
    }

    private List<Entry> entries(Set<Permission> permissions, ActiveEnvironment environment) {
        return registry.all().stream()
                .filter(tool -> permissions.contains(tool.definition().requiredPermission()))
                .filter(tool -> PolicyRules.permitsAutonomy(tool.definition().riskLevel(),
                        environment.autonomyLevel()))
                .map(tool -> new Entry(tool, PolicyRules.requiresApproval(tool.definition(),
                        environment.autonomyLevel())))
                .toList();
    }
}
