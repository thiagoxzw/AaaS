package com.devopsaaas.tool.api;

/**
 * A domain operation the agent may request. It depends only on ports (for example {@code ContainerRuntime}),
 * never on Docker, HTTP or the database (docs/05-contratos-das-ferramentas.md, section 1).
 *
 * @param <I> the flat input record; the executor parses and validates the LLM's JSON into it
 */
public interface Tool<I extends ToolInput> {

    ToolDefinition definition();

    Class<I> inputType();

    ToolResult execute(ToolExecutionContext context, I input);
}
