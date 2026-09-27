package com.devopsaaas.llm;

public record LlmUsage(int inputTokens, int outputTokens) {

    public static final LlmUsage NONE = new LlmUsage(0, 0);
}
