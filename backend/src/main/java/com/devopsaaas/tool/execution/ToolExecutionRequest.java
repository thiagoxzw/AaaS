package com.devopsaaas.tool.execution;

import com.devopsaaas.tool.policy.PolicyContext;
import com.devopsaaas.tool.policy.ToolProposal;
import java.util.UUID;

/** One proposal to evaluate and, if allowed, run. {@code llmCallId} is null until slice 4. */
public record ToolExecutionRequest(PolicyContext context, UUID agentExecutionId, UUID llmCallId, int seq,
        ToolProposal proposal) {
}
