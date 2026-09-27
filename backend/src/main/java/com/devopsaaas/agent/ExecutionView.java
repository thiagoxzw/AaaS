package com.devopsaaas.agent;

import com.devopsaaas.tool.api.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An execution as the API shows it (docs/03-arquitetura.md, section 6.3). {@code actions} is a projection of
 * the recorded tool calls, never of the model's text: an answer claiming "I restarted it" cannot add an
 * action that did not happen.
 */
public record ExecutionView(
        UUID executionId,
        UUID conversationId,
        AgentExecutionStatus status,
        String statusReason,
        Answer answer,
        List<Action> actions,
        Budget budget,
        Usage usage,
        String llmModel,
        String promptVersion,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public ExecutionView {
        actions = List.copyOf(actions);
    }

    /** {@code complete=false}: the model's output was cut by its token limit; the text is partial. */
    public record Answer(String text, boolean complete) {
    }

    /** {@code target} is the logical service name, never the container name. */
    public record Action(int seq, String tool, String target, RiskLevel risk, String status, String denialReason,
            String errorCode, Long durationMs) {
    }

    public record Budget(int toolCalls, int maxToolCalls, int llmIterations, int maxLlmIterations, long activeMs,
            long maxActiveMs) {
    }

    public record Usage(long inputTokens, long outputTokens, BigDecimal estimatedCostUsd) {
    }
}
