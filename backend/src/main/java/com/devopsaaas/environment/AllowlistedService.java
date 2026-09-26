package com.devopsaaas.environment;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.time.Timestamps;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A service the agent may see and act on (RF-11). The LLM only ever handles {@code name}; the real
 * {@code containerName} is known to the backend alone (docs/04-modelo-de-dados.md, section 4.4).
 */
@Entity
@Table(name = "environment_service")
public class AllowlistedService {

    @Id
    private UUID id;

    private UUID organizationId;
    private UUID environmentId;
    private String name;
    private String containerName;
    private String description;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected AllowlistedService() {
    }

    static AllowlistedService create(Environment environment, String name, String containerName,
            String description) {
        AllowlistedService service = new AllowlistedService();
        service.id = Ids.newId();
        service.organizationId = environment.getOrganizationId();
        service.environmentId = environment.getId();
        service.name = name;
        service.containerName = containerName;
        service.description = description;
        service.enabled = true;
        service.createdAt = Timestamps.now();
        service.updatedAt = service.createdAt;
        return service;
    }

    void changeDescription(String value) {
        description = value;
        updatedAt = Timestamps.now();
    }

    void changeEnabled(boolean value) {
        enabled = value;
        updatedAt = Timestamps.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public String getName() {
        return name;
    }

    public String getContainerName() {
        return containerName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
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
