package com.devopsaaas.tool.container;

import java.io.Serial;

/** A runtime failure, categorized so tools and the executor can decide what it means. */
public class ContainerRuntimeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Category {
        /** The container does not exist in the runtime. */
        NOT_FOUND,
        /** The runtime (or its proxy) could not be reached; possibly transient. */
        UNAVAILABLE,
        /** The proxy refused the operation: a configuration error, never retried. */
        FORBIDDEN,
        UNEXPECTED
    }

    private final Category category;

    public ContainerRuntimeException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public ContainerRuntimeException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
