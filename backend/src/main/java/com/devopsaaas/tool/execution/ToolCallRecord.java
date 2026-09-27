package com.devopsaaas.tool.execution;

import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.policy.DenialReason;
import java.time.Instant;
import java.util.UUID;

/**
 * A read-only view of one recorded tool call, for projections outside the tool module (the execution's
 * {@code actions[]} and the history sent back to the LLM). Arguments and output are already cleaned.
 */
public record ToolCallRecord(
        UUID id,
        UUID agentExecutionId,
        UUID llmCallId,
        int seq,
        String llmToolCallId,
        String toolName,
        RiskLevel riskLevel,
        UUID targetServiceId,
        String arguments,
        String rationale,
        ToolExecutionStatus status,
        DenialReason denialReason,
        ToolErrorCode errorCode,
        String errorMessage,
        String output,
        boolean outputTruncated,
        Long durationMs,
        Instant createdAt,
        Instant finishedAt) {

    static ToolCallRecord of(ToolExecution execution) {
        return new ToolCallRecord(execution.getId(), execution.getAgentExecutionId(), execution.getLlmCallId(),
                execution.getSeq(), execution.getLlmToolCallId(), execution.getToolName(), execution.getRiskLevel(),
                execution.getTargetServiceId(), execution.getArguments(), execution.getRationale(),
                execution.getStatus(), execution.getDenialReason(), execution.getErrorCode(),
                execution.getErrorMessage(), execution.getOutput(), execution.isOutputTruncated(),
                execution.getDurationMs(), execution.getCreatedAt(), execution.getFinishedAt());
    }
}
