package com.devopsaaas.tool.policy;

import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.environment.EnvironmentDirectory.ActiveEnvironment;
import com.devopsaaas.shared.security.PermissionLookup;
import com.devopsaaas.tool.api.ToolDefinition;
import com.devopsaaas.tool.container.ContainerRef;
import com.devopsaaas.tool.container.TargetResolver;
import com.devopsaaas.tool.registry.RegisteredTool;
import com.devopsaaas.tool.registry.ToolRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The validation chain of docs/03-arquitetura.md, section 5.2, in order. The first "no" ends the chain with
 * its reason. Deterministic code only: the LLM cannot skip or influence any step, and any unexpected error
 * fails closed (DENY with POLICY_ERROR).
 */
@Component
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final EnvironmentDirectory environments;
    private final ToolRegistry registry;
    private final ArgumentBinder binder;
    private final TargetResolver targets;
    private final PermissionLookup permissions;

    PolicyEngine(EnvironmentDirectory environments, ToolRegistry registry, ArgumentBinder binder,
            TargetResolver targets, PermissionLookup permissions) {
        this.environments = environments;
        this.registry = registry;
        this.binder = binder;
        this.targets = targets;
        this.permissions = permissions;
    }

    public PolicyDecision evaluate(PolicyContext context, ToolProposal proposal) {
        return evaluate(context, proposal, false);
    }

    /**
     * The same chain for a call a human already approved (slice 7), run again with the state of NOW: autonomy,
     * allowlist and the requester's permissions may have changed since the proposal. Two steps differ: the
     * budget was already counted when the call was proposed, and "requires approval" is satisfied.
     */
    public PolicyDecision evaluateApproved(PolicyContext context, ToolProposal proposal) {
        return evaluate(context, proposal, true);
    }

    private PolicyDecision evaluate(PolicyContext context, ToolProposal proposal, boolean approved) {
        try {
            return chain(context, proposal, approved);
        } catch (RuntimeException exception) {
            log.error("Policy evaluation failed; denying the proposal", exception);
            return PolicyDecision.deny(DenialReason.POLICY_ERROR, "The proposal could not be evaluated.",
                    registry.find(proposal.toolName()).orElse(null));
        }
    }

    private PolicyDecision chain(PolicyContext context, ToolProposal proposal, boolean approved) {
        Optional<ActiveEnvironment> environment =
                environments.findActive(context.organizationId(), context.environmentId());
        if (environment.isEmpty()) {
            return PolicyDecision.deny(DenialReason.ENVIRONMENT_UNAVAILABLE,
                    "The environment does not exist or is disabled.", null);
        }

        // 1. The tool exists in the closed catalog.
        Optional<RegisteredTool> found = registry.find(proposal.toolName());
        if (found.isEmpty()) {
            return PolicyDecision.deny(DenialReason.UNKNOWN_TOOL, "There is no such tool.", null);
        }
        RegisteredTool tool = found.get();
        ToolDefinition definition = tool.definition();

        // 2. The environment's autonomy level allows this risk.
        if (!PolicyRules.permitsAutonomy(definition.riskLevel(), environment.get().autonomyLevel())) {
            return PolicyDecision.deny(DenialReason.NOT_ALLOWED_BY_AUTONOMY,
                    "The environment's autonomy level does not allow this tool.", tool);
        }

        // 3. The arguments are valid for the tool's typed input.
        ArgumentBinder.Binding binding = binder.bind(proposal.argumentsJson(), tool.tool().inputType());
        if (binding instanceof ArgumentBinder.Rejected rejected) {
            return PolicyDecision.deny(DenialReason.INVALID_ARGUMENTS, rejected.problem(), tool);
        }
        ArgumentBinder.Bound bound = (ArgumentBinder.Bound) binding;

        // 4. The target is an enabled service of this environment's allowlist.
        ContainerRef target = null;
        if (definition.hasTarget()) {
            String serviceName = ArgumentBinder.componentValue(bound.input(), definition.targetParameter());
            Optional<ContainerRef> resolved =
                    targets.resolve(context.organizationId(), context.environmentId(), serviceName);
            if (resolved.isEmpty()) {
                return PolicyDecision.deny(DenialReason.RESOURCE_NOT_ALLOWED,
                        "The service is not in this environment's allowlist.", tool, bound, null);
            }
            target = resolved.get();
        }

        // 5. The requester has the permission right now (reloaded, never cached in a token).
        if (!permissions.currentPermissions(context.organizationId(), context.requestedBy())
                .contains(definition.requiredPermission())) {
            return PolicyDecision.deny(DenialReason.INSUFFICIENT_PERMISSION,
                    "The requesting user lacks the permission this tool requires.", tool, bound, target);
        }

        // 6. There is budget left in the execution (an approved call was counted when it was proposed).
        if (!approved && context.remainingToolCalls() <= 0) {
            return PolicyDecision.deny(DenialReason.BUDGET_EXCEEDED, "The execution has no tool calls left.",
                    tool, bound, target);
        }

        // 7. Risky actions wait for a human decision.
        if (!approved && PolicyRules.requiresApproval(definition, environment.get().autonomyLevel())) {
            return PolicyDecision.of(PolicyOutcome.REQUIRE_APPROVAL, tool, bound, target);
        }
        return PolicyDecision.of(PolicyOutcome.ALLOW, tool, bound, target);
    }
}
