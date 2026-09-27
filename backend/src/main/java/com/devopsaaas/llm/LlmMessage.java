package com.devopsaaas.llm;

import java.util.List;
import java.util.Objects;

/** A provider-neutral conversation message. */
public sealed interface LlmMessage {

    record User(String text) implements LlmMessage {

        public User {
            Objects.requireNonNull(text);
        }
    }

    /** A previous model turn: its text and the tool calls it proposed. */
    record Assistant(String text, List<LlmToolCall> toolCalls) implements LlmMessage {

        public Assistant {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /** What the backend observed for one proposed call: a tool result, a denial or a failure. Untrusted data. */
    record ToolResult(String toolCallId, String toolName, String content) implements LlmMessage {

        public ToolResult {
            Objects.requireNonNull(content);
        }
    }
}
