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

    /**
     * The same evaluated call, denied: for a check made after the policy that finds the call cannot go on
     * (slice 9a: arguments that storing would mask). It only ever narrows a decision.
     */
    public PolicyDecision denied(DenialReason reason, String detail) {
        return new PolicyDecision(PolicyOutcome.DENY, reason, detail, tool, input, target, canonicalArguments,
                argumentsHash);
    }

    /** A String argument of the validated input by name (slice 8: the declared justification), if any. */
    public Optional<String> argument(String name) {
        return input == null || name == null ? Optional.empty()
                : Optional.ofNullable(ArgumentBinder.componentValue(input, name));
    }

    public Optional<ContainerRef> resolvedTarget() {
        return Optional.ofNullable(target);
    }
}
