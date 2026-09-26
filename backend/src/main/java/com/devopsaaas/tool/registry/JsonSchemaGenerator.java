package com.devopsaaas.tool.registry;

import com.devopsaaas.tool.api.ToolInput;
import java.util.Map;

/**
 * Produces the JSON Schema of a tool input, sent to the LLM as the tool's parameter contract. Behind an
 * interface so the implementation can change (for example to a library) without touching the registry.
 */
public interface JsonSchemaGenerator {

    /**
     * @throws UnsupportedInputException if the input type cannot be described by this generator
     */
    Map<String, Object> schemaFor(Class<? extends ToolInput> inputType);

    class UnsupportedInputException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnsupportedInputException(String message) {
            super(message);
        }
    }
}
