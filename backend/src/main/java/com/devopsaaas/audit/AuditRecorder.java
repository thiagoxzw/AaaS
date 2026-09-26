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
        AuditEvent event = new AuditEvent(
                Ids.newId(),
                entry.actor().organizationId(),
                Timestamps.now(),
                AuditActorType.USER,
                entry.actor().userId(),
                entry.actor().email(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.outcome(),
                jsonMapper.writeValueAsString(entry.details()),
                MDC.get(RequestIdFilter.MDC_KEY));
        entityManager.persist(event);
    }
}
