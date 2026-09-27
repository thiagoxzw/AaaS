package com.devopsaaas.agent;

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

/** A conversation belongs to one environment: "which demo-api?" never has two answers (doc 04, 4.5). */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    private UUID id;

    private UUID organizationId;
    private UUID environmentId;
    private UUID createdBy;
    private String title;

    @Enumerated(EnumType.STRING)
    private ConversationStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected Conversation() {
    }

    static Conversation open(UUID organizationId, UUID environmentId, UUID createdBy, String title) {
        Conversation conversation = new Conversation();
        conversation.id = Ids.newId();
        conversation.organizationId = organizationId;
        conversation.environmentId = environmentId;
        conversation.createdBy = createdBy;
        conversation.title = title;
        conversation.status = ConversationStatus.OPEN;
        conversation.createdAt = Timestamps.now();
        conversation.updatedAt = conversation.createdAt;
        return conversation;
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

    public UUID getCreatedBy() {
        return createdBy;
    }

    public String getTitle() {
        return title;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
