package com.devopsaaas.audit;

import com.devopsaaas.shared.security.CurrentUser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** What to record. Details hold only values produced by the application itself, never raw request input. */
public final class AuditEntry {

    private final AuditActorType actorType;
    private final UUID organizationId;
    private final UUID userId;
    private final String actorLabel;
    private final AuditAction action;
    private final AuditResourceType resourceType;
    private final UUID resourceId;
    private final Map<String, Object> details = new LinkedHashMap<>();
    private AuditOutcome outcome = AuditOutcome.SUCCESS;
    private String toolName;
    private UUID agentExecutionId;
    private UUID toolExecutionId;

    private AuditEntry(AuditActorType actorType, UUID organizationId, UUID userId, String actorLabel,
            AuditAction action, AuditResourceType resourceType, UUID resourceId) {
        this.actorType = actorType;
        this.organizationId = organizationId;
        this.userId = userId;
        this.actorLabel = actorLabel;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    /** A person acted directly (for example through the API). */
    public static AuditEntry byUser(CurrentUser actor, AuditAction action, AuditResourceType resourceType,
            UUID resourceId) {
        return new AuditEntry(AuditActorType.USER, actor.organizationId(), actor.userId(), actor.email(), action,
                resourceType, resourceId);
    }

    /**
     * The agent acted technically, on behalf of the user who asked: answers both "who executed?" (AGENT) and
     * "at whose request?" ({@code on_behalf_of_user_id}).
     */
    public static AuditEntry byAgentOnBehalfOf(UUID organizationId, UUID requestedBy, AuditAction action,
            AuditResourceType resourceType, UUID resourceId) {
        return new AuditEntry(AuditActorType.AGENT, organizationId, requestedBy, "agent", action, resourceType,
                resourceId);
    }

    public AuditEntry toolName(String value) {
        this.toolName = value;
        return this;
    }

    public AuditEntry agentExecutionId(UUID value) {
        this.agentExecutionId = value;
        return this;
    }

    public AuditEntry toolExecutionId(UUID value) {
        this.toolExecutionId = value;
        return this;
    }

    public AuditEntry detail(String key, Object value) {
        details.put(key, value instanceof Enum<?> constant ? constant.name() : value);
        return this;
    }

    public AuditEntry outcome(AuditOutcome value) {
        this.outcome = value;
        return this;
    }

    AuditActorType actorType() {
        return actorType;
    }

    UUID organizationId() {
        return organizationId;
    }

    UUID userId() {
        return userId;
    }

    String actorLabel() {
        return actorLabel;
    }

    String toolNameValue() {
        return toolName;
    }

    UUID agentExecutionIdValue() {
        return agentExecutionId;
    }

    UUID toolExecutionIdValue() {
        return toolExecutionId;
    }

    AuditAction action() {
        return action;
    }

    AuditResourceType resourceType() {
        return resourceType;
    }

    UUID resourceId() {
        return resourceId;
    }

    AuditOutcome outcome() {
        return outcome;
    }

    Map<String, Object> details() {
        return Collections.unmodifiableMap(details);
    }
}
