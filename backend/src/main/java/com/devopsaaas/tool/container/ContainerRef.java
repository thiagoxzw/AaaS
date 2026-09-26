package com.devopsaaas.tool.container;

import java.util.Objects;
import java.util.UUID;

/**
 * A reference to an allowlisted container. The constructor is package-private: only {@link TargetResolver}
 * creates references, and only from an ENABLED allowlist entry of an ACTIVE environment. A tool therefore
 * cannot address a container outside the allowlist, not even by mistake (docs/05-contratos-das-ferramentas.md,
 * section 5). It is a final class and not a record because a public record cannot hide its constructor.
 */
public final class ContainerRef {

    private final UUID serviceId;
    private final String serviceName;
    private final String containerName;

    ContainerRef(UUID serviceId, String serviceName, String containerName) {
        this.serviceId = Objects.requireNonNull(serviceId);
        this.serviceName = Objects.requireNonNull(serviceName);
        this.containerName = Objects.requireNonNull(containerName);
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
