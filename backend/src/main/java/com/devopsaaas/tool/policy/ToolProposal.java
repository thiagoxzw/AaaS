package com.devopsaaas.tool.policy;

/**
 * A tool call as proposed by the LLM: untrusted input, validated like any external request.
 *
 * @param argumentsJson raw JSON arguments exactly as proposed
 */
public record ToolProposal(String toolName, String argumentsJson, String llmToolCallId, String rationale) {
}
