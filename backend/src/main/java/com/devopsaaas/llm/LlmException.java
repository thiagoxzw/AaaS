package com.devopsaaas.llm;

import java.io.Serial;

/** A failed model call, by category. Messages never carry provider payloads or prompts. */
public class LlmException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public enum Category {
        TIMEOUT, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE,
        /** The provider refused the request itself (bad credentials, invalid request): retrying cannot help. */
        REJECTED,
        /** The provider account has no credit or quota left: retrying cannot help until someone adds it. */
        QUOTA_EXHAUSTED
    }

    private final Category category;

    public LlmException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public LlmException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
