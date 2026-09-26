package com.devopsaaas.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable audit record. {@code @Immutable} stops Hibernate from ever issuing an UPDATE; the database
 * trigger in V4 is the real guarantee (it also blocks DELETE and TRUNCATE).
 */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    private UUID id;

    private UUID organizationId;
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    private AuditActorType actorType;

    private UUID actorUserId;
    private UUID onBehalfOfUserId;
    private String actorLabel;

    @Enumerated(EnumType.STRING)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    private AuditResourceType resourceType;

    private UUID resourceId;
    private String toolName;
    private UUID agentExecutionId;
    private UUID toolExecutionId;

    @Enumerated(EnumType.STRING)
    private AuditOutcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    private String traceId;

    protected AuditEvent() {
    }

    AuditEvent(UUID id, UUID organizationId, Instant occurredAt, AuditActorType actorType, UUID actorUserId,
            UUID onBehalfOfUserId, String actorLabel, AuditAction action, AuditResourceType resourceType,
            UUID resourceId, AuditOutcome outcome, String details, String traceId) {
        this.id = id;
        this.organizationId = organizationId;
        this.occurredAt = occurredAt;
        this.actorType = actorType;
        this.actorUserId = actorUserId;
        this.onBehalfOfUserId = onBehalfOfUserId;
        this.actorLabel = actorLabel;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.outcome = outcome;
        this.details = details;
        this.traceId = traceId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public UUID getOnBehalfOfUserId() {
        return onBehalfOfUserId;
    }

    public String getToolName() {
        return toolName;
    }

    public UUID getAgentExecutionId() {
        return agentExecutionId;
    }

    public UUID getToolExecutionId() {
        return toolExecutionId;
    }

    void linkTool(String tool, UUID agentExecution, UUID toolExecution) {
        this.toolName = tool;
        this.agentExecutionId = agentExecution;
        this.toolExecutionId = toolExecution;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditResourceType getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getDetails() {
        return details;
    }

    public String getTraceId() {
        return traceId;
    }
}
