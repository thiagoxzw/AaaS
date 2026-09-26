package com.devopsaaas.environment;

/**
 * How much the agent may do without a human in the loop (ADR-0008). A security setting, never an LLM
 * decision. {@code AUTOMATED} exists in the model but is rejected until after the MVP.
 */
public enum AutonomyLevel {
    OBSERVE_ONLY,
    ASSISTED,
    AUTOMATED
}
