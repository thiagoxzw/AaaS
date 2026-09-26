package com.devopsaaas.tool.policy;

import com.devopsaaas.tool.api.ToolInput;
import com.devopsaaas.tool.container.ContainerRef;
import com.devopsaaas.tool.registry.RegisteredTool;
import java.util.Optional;

/**
 * Result of the validation chain. On ALLOW or REQUIRE_APPROVAL it carries everything the executor needs:
 * the tool, the validated input, the resolved target and the canonical arguments with their hash (which a
 * future approval is bound to).
 */
public record PolicyDecision(
        PolicyOutcome outcome,
        DenialReason denialReason,
        String detail,
        RegisteredTool tool,
        ToolInput input,
        ContainerRef target,
        String canonicalArguments,
        String argumentsHash) {

    static PolicyDecision deny(DenialReason reason, String detail, RegisteredTool tool) {
        return new PolicyDecision(PolicyOutcome.DENY, reason, detail, tool, null, null, null, null);
    }

    static PolicyDecision deny(DenialReason reason, String detail, RegisteredTool tool, ArgumentBinder.Bound bound,
            ContainerRef target) {
        return new PolicyDecision(PolicyOutcome.DENY, reason, detail, tool, bound.input(), target,
                bound.canonicalJson(), bound.hash());
    }

    static PolicyDecision of(PolicyOutcome outcome, RegisteredTool tool, ArgumentBinder.Bound bound,
            ContainerRef target) {
        return new PolicyDecision(outcome, null, null, tool, bound.input(), target, bound.canonicalJson(),
                bound.hash());
    }

    public Optional<RegisteredTool> registeredTool() {
        return Optional.ofNullable(tool);
    }

    public Optional<ContainerRef> resolvedTarget() {
        return Optional.ofNullable(target);
    }
}
