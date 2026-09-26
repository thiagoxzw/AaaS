package com.devopsaaas.environment;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.time.Timestamps;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "environment")
public class Environment {

    @Id
    private UUID id;

    private UUID organizationId;
    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private EnvironmentType type;

    @Enumerated(EnumType.STRING)
    private EnvironmentTier tier;

    @Enumerated(EnumType.STRING)
    private AutonomyLevel autonomyLevel;

    /** Logical name of a connection defined in configuration; never a URL or a secret (RF-13). */
    private String connectionRef;

    @Enumerated(EnumType.STRING)
    private EnvironmentStatus status;

    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected Environment() {
    }

    static Environment create(UUID organizationId, UUID createdBy, String name, String description,
            EnvironmentType type, EnvironmentTier tier, AutonomyLevel autonomyLevel, String connectionRef) {
        Environment environment = new Environment();
        environment.id = Ids.newId();
        environment.organizationId = organizationId;
        environment.createdBy = createdBy;
        environment.name = name;
        environment.description = description;
        environment.type = type;
        environment.tier = tier;
        environment.autonomyLevel = autonomyLevel;
        environment.connectionRef = connectionRef;
        environment.status = EnvironmentStatus.ACTIVE;
        environment.createdAt = Timestamps.now();
        environment.updatedAt = environment.createdAt;
        return environment;
    }

    void changeDescription(String value) {
        description = value;
        touch();
    }

    void changeTier(EnvironmentTier value) {
        tier = value;
        touch();
    }

    void changeAutonomyLevel(AutonomyLevel value) {
        autonomyLevel = value;
        touch();
    }

    void changeStatus(EnvironmentStatus value) {
        status = value;
        touch();
    }

    private void touch() {
        updatedAt = Timestamps.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public EnvironmentType getType() {
        return type;
    }

    public EnvironmentTier getTier() {
        return tier;
    }

    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    public String getConnectionRef() {
        return connectionRef;
    }

    public EnvironmentStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
