package com.devopsaaas.environment.api;

import com.devopsaaas.environment.AllowlistedService;
import com.devopsaaas.environment.AutonomyLevel;
import com.devopsaaas.environment.Environment;
import com.devopsaaas.environment.EnvironmentStatus;
import com.devopsaaas.environment.EnvironmentTier;
import com.devopsaaas.environment.EnvironmentType;
import java.time.Instant;
import java.util.UUID;

final class EnvironmentResponses {

    private EnvironmentResponses() {
    }

    record EnvironmentView(UUID id, String name, String description, EnvironmentType type, EnvironmentTier tier,
            AutonomyLevel autonomyLevel, String connectionRef, EnvironmentStatus status, Instant createdAt,
            Instant updatedAt, Long version) {

        static EnvironmentView from(Environment environment) {
            return new EnvironmentView(environment.getId(), environment.getName(), environment.getDescription(),
                    environment.getType(), environment.getTier(), environment.getAutonomyLevel(),
                    environment.getConnectionRef(), environment.getStatus(), environment.getCreatedAt(),
                    environment.getUpdatedAt(), environment.getVersion());
        }
    }

    record ServiceView(UUID id, UUID environmentId, String name, String containerName, String description,
            boolean enabled, Instant createdAt, Instant updatedAt, Long version) {

        static ServiceView from(AllowlistedService service) {
            return new ServiceView(service.getId(), service.getEnvironmentId(), service.getName(),
                    service.getContainerName(), service.getDescription(), service.isEnabled(),
                    service.getCreatedAt(), service.getUpdatedAt(), service.getVersion());
        }
    }
}
