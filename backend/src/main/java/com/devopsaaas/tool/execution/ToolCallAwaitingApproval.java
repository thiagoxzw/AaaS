package com.devopsaaas.tool.execution;

import com.devopsaaas.tool.api.RiskLevel;
import java.util.UUID;

/**
 * Published inside the transaction that records a call as WAITING_APPROVAL, so whoever turns it into an
 * approval does it atomically with the call: there is never a waiting call without its approval (slice 7).
 * Every value comes from the policy decision and the tool definition, except {@code agentJustification},
 * which is the model's text and is untrusted.
 */
public record ToolCallAwaitingApproval(
        UUID organizationId,
        UUID agentExecutionId,
        UUID toolExecutionId,
        UUID requestedBy,
        String toolName,
        RiskLevel riskLevel,
        String argumentsHash,
        String impactDescription,
        String agentJustification) {
}
