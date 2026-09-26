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
}
