package com.devopsaaas.llm;

import java.util.List;
import java.util.Objects;

/** One model turn: text, proposed tool calls, why it stopped and the tokens it used. */
public record LlmResponse(String text, List<LlmToolCall> toolCalls, LlmFinishReason finishReason, LlmUsage usage) {

    public LlmResponse {
        toolCalls = List.copyOf(toolCalls);
        Objects.requireNonNull(finishReason);
        Objects.requireNonNull(usage);
    }
}
