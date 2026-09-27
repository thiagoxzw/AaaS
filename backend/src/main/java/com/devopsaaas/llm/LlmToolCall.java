package com.devopsaaas.llm;

/**
 * A tool call proposed by the model. Nothing about it is trusted: the name may not exist and the arguments
 * may not be valid JSON; the tool policy decides.
 */
public record LlmToolCall(String id, String name, String argumentsJson) {
}
