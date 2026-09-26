package com.devopsaaas.audit;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.observability.RequestIdFilter;
import com.devopsaaas.shared.time.Timestamps;
import jakarta.persistence.EntityManager;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records audit events inside the caller's transaction. {@link Propagation#MANDATORY} makes it impossible
 * to record outside one: the event commits or rolls back together with the change it describes.
 */
@Service
public class AuditRecorder {

    private final EntityManager entityManager;
    private final JsonMapper jsonMapper;

    AuditRecorder(EntityManager entityManager, JsonMapper jsonMapper) {
        this.entityManager = entityManager;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        boolean byAgent = entry.actorType() == AuditActorType.AGENT;
        AuditEvent event = new AuditEvent(
                Ids.newId(),
                entry.organizationId(),
                Timestamps.now(),
                entry.actorType(),
                byAgent ? null : entry.userId(),
                byAgent ? entry.userId() : null,
                entry.actorLabel(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.outcome(),
                jsonMapper.writeValueAsString(entry.details()),
                MDC.get(RequestIdFilter.MDC_KEY));
        event.linkTool(entry.toolNameValue(), entry.agentExecutionIdValue(), entry.toolExecutionIdValue());
        entityManager.persist(event);
    }
}
