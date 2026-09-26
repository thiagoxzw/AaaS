package com.devopsaaas.environment.api;

import com.devopsaaas.environment.AutonomyLevel;
import com.devopsaaas.environment.EnvironmentCommands;
import com.devopsaaas.environment.EnvironmentStatus;
import com.devopsaaas.environment.EnvironmentTier;
import com.devopsaaas.environment.EnvironmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class EnvironmentRequests {

    private EnvironmentRequests() {
    }

    record CreateEnvironment(
            @NotBlank @Pattern(regexp = Patterns.LOGICAL_NAME) String name,
            @Size(max = 500) String description,
            @NotNull EnvironmentType type,
            @NotNull EnvironmentTier tier,
            @NotNull AutonomyLevel autonomyLevel,
            @NotBlank @Pattern(regexp = Patterns.CONNECTION_REF) String connectionRef) {

        EnvironmentCommands.NewEnvironment toCommand() {
            return new EnvironmentCommands.NewEnvironment(name, description, type, tier, autonomyLevel,
                    connectionRef);
        }
    }

    record UpdateEnvironment(
            @Size(max = 500) String description,
            EnvironmentTier tier,
            AutonomyLevel autonomyLevel,
            EnvironmentStatus status,
            @NotNull Long version) {

        EnvironmentCommands.EnvironmentChanges toCommand() {
            return new EnvironmentCommands.EnvironmentChanges(description, tier, autonomyLevel, status, version);
        }
    }

    record CreateService(
            @NotBlank @Pattern(regexp = Patterns.LOGICAL_NAME) String name,
            @NotBlank @Pattern(regexp = Patterns.CONTAINER_NAME) String containerName,
            @Size(max = 500) String description) {

        EnvironmentCommands.NewService toCommand() {
            return new EnvironmentCommands.NewService(name, containerName, description);
        }
    }

    record UpdateService(Boolean enabled, @Size(max = 500) String description, @NotNull Long version) {

        EnvironmentCommands.ServiceChanges toCommand() {
            return new EnvironmentCommands.ServiceChanges(enabled, description, version);
        }
    }
}
