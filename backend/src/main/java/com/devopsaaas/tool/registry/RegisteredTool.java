package com.devopsaaas.tool.registry;

import com.devopsaaas.tool.api.Tool;
import com.devopsaaas.tool.api.ToolDefinition;
import java.util.Map;

/** A validated tool with its definition and generated input schema. */
public record RegisteredTool(Tool<?> tool, ToolDefinition definition, Map<String, Object> inputSchema) {

    public RegisteredTool {
        inputSchema = Map.copyOf(inputSchema);
    }

    public String name() {
        return definition.name();
    }
}
