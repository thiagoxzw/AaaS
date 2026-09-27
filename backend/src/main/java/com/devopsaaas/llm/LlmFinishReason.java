package com.devopsaaas.llm;

/** Provider-neutral stop reason. {@code LENGTH}: the output was cut by the token limit. */
public enum LlmFinishReason {
    TOOL_CALLS, STOP, LENGTH
}
