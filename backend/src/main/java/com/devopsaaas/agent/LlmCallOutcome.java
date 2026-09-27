package com.devopsaaas.agent;

/** Stored finish reason of an LLM call: the provider-neutral reasons, plus ERROR for a failed call. */
public enum LlmCallOutcome {
    TOOL_CALLS, STOP, LENGTH, ERROR
}
