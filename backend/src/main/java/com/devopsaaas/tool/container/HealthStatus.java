package com.devopsaaas.tool.container;

public enum HealthStatus {
    NONE,
    STARTING,
    HEALTHY,
    UNHEALTHY,
    /**
     * Reported instead of the runtime's value when the container is not running: Docker keeps the last health
     * of a stopped container, and "UNHEALTHY" on a stopped container reads as current evidence (slice 6.1).
     */
    NOT_APPLICABLE;

    /** What is shown to people and to the agent. Diagnostics work on the raw value of the snapshot. */
    public static HealthStatus reported(ContainerState state, HealthStatus health) {
        return state == ContainerState.RUNNING ? health : NOT_APPLICABLE;
    }
}
