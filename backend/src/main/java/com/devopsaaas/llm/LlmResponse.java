package com.devopsaaas.llm;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One model turn: text, proposed tool calls, why it stopped, the tokens it used and what it cost. The cost is
 * the provider's estimate from its configured price table; the scripted provider costs nothing.
 */
public record LlmResponse(String text, List<LlmToolCall> toolCalls, LlmFinishReason finishReason, LlmUsage usage,
        BigDecimal estimatedCostUsd) {

    public LlmResponse {
        toolCalls = List.copyOf(toolCalls);
        Objects.requireNonNull(finishReason);
        Objects.requireNonNull(usage);
        Objects.requireNonNull(estimatedCostUsd);
    }
}
