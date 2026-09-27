package com.devopsaaas.tool.container;

import java.util.Objects;
import java.util.UUID;

/**
 * A reference to an allowlisted container. The constructor is package-private: only {@link TargetResolver}
 * creates references, and only from an ENABLED allowlist entry of an ACTIVE environment. A tool therefore
 * cannot address a container outside the allowlist, not even by mistake (docs/05-contratos-das-ferramentas.md,
 * section 5). It is a final class and not a record because a public record cannot hide its constructor.
 *
 * <p>It also carries the environment's {@code connectionRef}, so an adapter knows which configured runtime to
 * call without the tool ever seeing a URL.
 */
public final class ContainerRef {

    private final UUID serviceId;
    private final String serviceName;
    private final String containerName;
    private final String connectionRef;

    ContainerRef(UUID serviceId, String serviceName, String containerName, String connectionRef) {
        this.serviceId = Objects.requireNonNull(serviceId);
        this.serviceName = Objects.requireNonNull(serviceName);
        this.containerName = Objects.requireNonNull(containerName);
        this.connectionRef = Objects.requireNonNull(connectionRef);
    }

    public UUID serviceId() {
        return serviceId;
    }

    /** The logical name the LLM sees, for example {@code demo-api}. */
    public String serviceName() {
        return serviceName;
    }

    /** The real container name. Known to the backend only; never sent to the LLM. */
    public String containerName() {
        return containerName;
    }

    /** Logical name of the runtime connection of the service's environment, for example {@code local}. */
    public String connectionRef() {
        return connectionRef;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContainerRef that && serviceId.equals(that.serviceId);
    }

    @Override
    public int hashCode() {
        return serviceId.hashCode();
    }

    @Override
    public String toString() {
        return "ContainerRef[service=" + serviceName + "]";
    }
}
