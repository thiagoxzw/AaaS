package com.devopsaaas.tool.api;

import java.util.List;

/** What a tool returns. Unexpected exceptions are handled by the executor, not modelled here. */
public sealed interface ToolResult {

    record Success(Object data, List<Finding> findings) implements ToolResult {

        public Success {
            findings = List.copyOf(findings);
        }
    }

    /** An expected failure. {@code transientError} allows a retry, and only for retryable (read-only) tools. */
    record Failure(ToolErrorCode code, String message, boolean transientError) implements ToolResult {
    }

    static ToolResult success(Object data) {
        return new Success(data, List.of());
    }

    static ToolResult success(Object data, List<Finding> findings) {
        return new Success(data, findings);
    }

    static ToolResult failure(ToolErrorCode code, String message, boolean transientError) {
        return new Failure(code, message, transientError);
    }
}
