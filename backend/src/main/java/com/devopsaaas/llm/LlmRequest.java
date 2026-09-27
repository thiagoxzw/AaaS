package com.devopsaaas.llm;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Everything the model sees in one turn: the system prompt, the conversation so far, and the tools already
 * filtered for the requester and the environment.
 */
public record LlmRequest(String systemPrompt, List<LlmMessage> messages, List<LlmToolSpec> tools,
        int maxOutputTokens, Duration timeout) {

    public LlmRequest {
        Objects.requireNonNull(systemPrompt);
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        Objects.requireNonNull(timeout);
    }
}
