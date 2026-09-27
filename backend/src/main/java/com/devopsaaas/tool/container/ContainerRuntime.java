package com.devopsaaas.tool.container;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Port to a container runtime (docs/05-contratos-das-ferramentas.md, section 9). It names the capability,
 * not Docker: production uses the Docker Engine adapter in {@code integration.docker}, and tests use an
 * in-memory fake. Every container operation takes {@link ContainerRef}, never a raw container name.
 */
public interface ContainerRuntime {

    /** One snapshot per reference, in order; a missing container is reported as {@code NOT_FOUND}. */
    List<ContainerSnapshot> list(Collection<ContainerRef> refs);

    ContainerSnapshot inspect(ContainerRef ref);

    ContainerLogs logs(ContainerRef ref, LogQuery query);

    void restart(ContainerRef ref, Duration gracefulStopTimeout);

    /**
     * Reaches the runtime of a configured connection (RF-12). The connection name comes from an environment,
     * which only an admin can create; it is never a URL.
     */
    RuntimeVersion version(String connectionRef);
}
