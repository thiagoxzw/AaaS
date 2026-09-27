package com.devopsaaas.llm.scripted;

import com.devopsaaas.llm.LlmException;
import com.devopsaaas.llm.LlmFinishReason;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A deterministic conversation: which user messages it answers ({@code when}) and what the "model" says in
 * each turn. Scripts drive the tests, including the malicious-LLM scenarios that obey injected instructions,
 * and let the stack run locally without an API key.
 *
 * @param repeatLastTurn when the turns run out, keep repeating the last one (a model stuck in a loop)
 */
public record LlmScript(String name, Pattern when, int priority, boolean repeatLastTurn, List<Turn> turns) {

    public LlmScript {
        Objects.requireNonNull(name);
        Objects.requireNonNull(when);
        turns = List.copyOf(turns);
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("Script " + name + " has no turns");
        }
    }

    /**
     * One model turn. {@code error} simulates a failed call; {@code text} may contain {@code {{lastToolResult}}},
     * replaced with the last tool result the backend sent.
     */
    public record Turn(String text, List<ToolCall> toolCalls, LlmFinishReason finishReason,
            LlmException.Category error) {

        public Turn {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /** {@code argumentsJson} is sent as is, so a script can also propose malformed JSON. */
    public record ToolCall(String name, String argumentsJson) {
    }
}
