package com.devopsaaas.tool.api;

import java.util.Map;

/** A deterministic observation computed by a tool from what it read, never by the LLM. */
public record Finding(String code, FindingSeverity severity, String message, Map<String, String> evidence) {

    public Finding {
        evidence = Map.copyOf(evidence);
    }
}
