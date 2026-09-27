package com.devopsaaas.llm;

import java.util.Map;

/** A tool as offered to the model: name, description and the JSON Schema of its input. */
public record LlmToolSpec(String name, String description, Map<String, Object> inputSchema) {

    public LlmToolSpec {
        inputSchema = Map.copyOf(inputSchema);
    }
}
