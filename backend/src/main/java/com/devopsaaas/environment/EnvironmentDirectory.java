package com.devopsaaas.environment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only view of environments and their allowlist for other modules (the tool policy). Every lookup is
 * scoped by organization, and only ACTIVE environments and ENABLED services are ever returned.
 */
@Service
public class EnvironmentDirectory {

    private final EnvironmentRepository environments;
    private final AllowlistedServiceRepository services;

    EnvironmentDirectory(EnvironmentRepository environments, AllowlistedServiceRepository services) {
        this.environments = environments;
        this.services = services;
    }

    public record ActiveEnvironment(UUID id, UUID organizationId, AutonomyLevel autonomyLevel) {
    }

    public record EnabledService(UUID id, UUID environmentId, String name, String containerName) {
    }

    @Transactional(readOnly = true)
    public Optional<ActiveEnvironment> findActive(UUID organizationId, UUID environmentId) {
        return environments.findByIdAndOrganizationId(environmentId, organizationId)
                .filter(environment -> environment.getStatus() == EnvironmentStatus.ACTIVE)
                .map(environment -> new ActiveEnvironment(environment.getId(), environment.getOrganizationId(),
                        environment.getAutonomyLevel()));
    }

    @Transactional(readOnly = true)
    public Optional<EnabledService> findEnabledService(UUID organizationId, UUID environmentId, String serviceName) {
        return findActive(organizationId, environmentId)
                .flatMap(environment -> services.findByEnvironmentIdAndOrganizationIdAndName(
                        environmentId, organizationId, serviceName))
                .filter(AllowlistedService::isEnabled)
                .map(service -> new EnabledService(service.getId(), service.getEnvironmentId(), service.getName(),
                        service.getContainerName()));
    }
}
