package com.devopsaaas.tool.api;

/** Expected tool failures (docs/05-contratos-das-ferramentas.md, section 6). */
public enum ToolErrorCode {
    TARGET_NOT_FOUND, INVALID_TARGET_STATE, RUNTIME_UNAVAILABLE, RUNTIME_FORBIDDEN, CAPACITY_EXCEEDED, INTERNAL_ERROR
}
