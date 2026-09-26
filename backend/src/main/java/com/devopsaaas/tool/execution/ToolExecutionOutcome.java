package com.devopsaaas.tool.execution;

import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.policy.DenialReason;
import java.util.UUID;

/**
 * What the caller (later, the orchestrator) learns about a call. Everything here is already sanitized and
 * redacted, and is safe to hand to the LLM as an observation.
 */
public record ToolExecutionOutcome(
        UUID toolExecutionId,
        ToolExecutionStatus status,
        DenialReason denialReason,
        String detail,
        String output,
        boolean outputTruncated,
        ToolErrorCode errorCode,
        int attempts) {
}
