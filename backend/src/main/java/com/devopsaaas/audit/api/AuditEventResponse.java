package com.devopsaaas.audit.api;

import com.devopsaaas.audit.AuditAction;
import com.devopsaaas.audit.AuditActorType;
import com.devopsaaas.audit.AuditEvent;
import com.devopsaaas.audit.AuditOutcome;
import com.devopsaaas.audit.AuditResourceType;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.UUID;

record AuditEventResponse(
        UUID id,
        Instant occurredAt,
        AuditActorType actorType,
        UUID actorUserId,
        String actorLabel,
        AuditAction action,
        AuditResourceType resourceType,
        UUID resourceId,
        AuditOutcome outcome,
        // Stored as jsonb by the application itself, so it is emitted as a JSON object.
        @JsonRawValue String details,
        String traceId) {

    static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getOccurredAt(),
                event.getActorType(),
                event.getActorUserId(),
                event.getActorLabel(),
                event.getAction(),
                event.getResourceType(),
                event.getResourceId(),
                event.getOutcome(),
                event.getDetails(),
                event.getTraceId());
    }
}
