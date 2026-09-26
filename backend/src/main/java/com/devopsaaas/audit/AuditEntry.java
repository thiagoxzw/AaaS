package com.devopsaaas.audit;

import com.devopsaaas.shared.security.CurrentUser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** What to record. Details hold only values produced by the application itself, never raw request input. */
public final class AuditEntry {

    private final CurrentUser actor;
    private final AuditAction action;
    private final AuditResourceType resourceType;
    private final UUID resourceId;
    private final Map<String, Object> details = new LinkedHashMap<>();
    private AuditOutcome outcome = AuditOutcome.SUCCESS;

    private AuditEntry(CurrentUser actor, AuditAction action, AuditResourceType resourceType, UUID resourceId) {
        this.actor = actor;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public static AuditEntry byUser(CurrentUser actor, AuditAction action, AuditResourceType resourceType,
            UUID resourceId) {
        return new AuditEntry(actor, action, resourceType, resourceId);
    }

    public AuditEntry detail(String key, Object value) {
        details.put(key, value instanceof Enum<?> constant ? constant.name() : value);
        return this;
    }

    public AuditEntry outcome(AuditOutcome value) {
        this.outcome = value;
        return this;
    }

    CurrentUser actor() {
        return actor;
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
