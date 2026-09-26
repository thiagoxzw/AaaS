package com.devopsaaas.tool.container;

import com.devopsaaas.environment.EnvironmentDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Resolves a logical service name proposed by the LLM against the environment's allowlist. */
@Component
public class TargetResolver {

    private final EnvironmentDirectory directory;

    TargetResolver(EnvironmentDirectory directory) {
        this.directory = directory;
    }

    public Optional<ContainerRef> resolve(UUID organizationId, UUID environmentId, String serviceName) {
        if (serviceName == null) {
            return Optional.empty();
        }
        return directory.findEnabledService(organizationId, environmentId, serviceName)
                .map(service -> new ContainerRef(service.id(), service.name(), service.containerName()));
    }
}
