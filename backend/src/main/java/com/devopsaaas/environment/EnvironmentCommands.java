package com.devopsaaas.environment;

/** Inputs of {@link EnvironmentManagement}, already validated at the API boundary. */
public final class EnvironmentCommands {

    private EnvironmentCommands() {
    }

    public record NewEnvironment(String name, String description, EnvironmentType type, EnvironmentTier tier,
            AutonomyLevel autonomyLevel, String connectionRef) {
    }

    /** Null fields are left unchanged. {@code version} must match the current one (optimistic locking). */
    public record EnvironmentChanges(String description, EnvironmentTier tier, AutonomyLevel autonomyLevel,
            EnvironmentStatus status, long version) {
    }

    public record NewService(String name, String containerName, String description) {
    }

    public record ServiceChanges(Boolean enabled, String description, long version) {
    }
}
