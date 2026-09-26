package com.devopsaaas.tool.api;

/**
 * Marker for tool input records. Inputs are flat records of scalars (strings, numbers, booleans, enums), so
 * the LLM can never pass arbitrary nested structures; the registry rejects anything else at startup.
 */
public interface ToolInput {
}
