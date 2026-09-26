package com.devopsaaas.tool.container;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Port to a container runtime (docs/05-contratos-das-ferramentas.md, section 9). It names the capability,
 * not Docker: the Docker Engine adapter arrives in slice 3, and tests use an in-memory fake. Every method
 * takes {@link ContainerRef}, never a raw container name.
 */
public interface ContainerRuntime {

    List<ContainerSnapshot> list(Collection<ContainerRef> refs);

    ContainerSnapshot inspect(ContainerRef ref);

    ContainerLogs logs(ContainerRef ref, LogQuery query);

    void restart(ContainerRef ref, Duration gracefulStopTimeout);
}
