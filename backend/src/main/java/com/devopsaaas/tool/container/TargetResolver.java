package com.devopsaaas.tool.container;

import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.environment.EnvironmentDirectory.EnabledService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only way to obtain a {@link ContainerRef}: from an ENABLED allowlist entry of an ACTIVE environment of
 * the caller's organization. The service name is matched exactly; nothing the LLM sends becomes a container
 * name.
 */
@Component
public class TargetResolver {

    private final EnvironmentDirectory directory;

    TargetResolver(EnvironmentDirectory directory) {
        this.directory = directory;
    }

    /** An allowlisted service with its reference and the admin-provided description. */
    public record AllowlistedTarget(ContainerRef ref, String description) {
    }

    public Optional<ContainerRef> resolve(UUID organizationId, UUID environmentId, String serviceName) {
        if (serviceName == null) {
            return Optional.empty();
        }
        return directory.findEnabledService(organizationId, environmentId, serviceName).map(TargetResolver::ref);
    }

    /** Every enabled service of the environment, ordered by name; empty when the environment is unavailable. */
    public List<AllowlistedTarget> resolveAll(UUID organizationId, UUID environmentId) {
        return directory.findEnabledServices(organizationId, environmentId).stream()
                .map(service -> new AllowlistedTarget(ref(service), service.description()))
                .toList();
    }

    private static ContainerRef ref(EnabledService service) {
        return new ContainerRef(service.id(), service.name(), service.containerName(), service.connectionRef());
    }
}
