package com.devopsaaas.tool.container;

import java.time.Instant;

/**
 * The only container data that leaves a runtime adapter. It deliberately has no field for environment
 * variables, command or mounts (RNF-SEG-10): data minimization happens before any redaction.
 */
public record ContainerSnapshot(
        String serviceName,
        ContainerState state,
        HealthStatus health,
        Integer exitCode,
        boolean oomKilled,
        int restartCount,
        Instant startedAt,
        Instant finishedAt,
        String image) {

    /** An allowlisted service whose container does not exist in the runtime. */
    public static ContainerSnapshot notFound(String serviceName) {
        return new ContainerSnapshot(serviceName, ContainerState.NOT_FOUND, HealthStatus.NONE, null, false, 0, null,
                null, null);
    }
}
