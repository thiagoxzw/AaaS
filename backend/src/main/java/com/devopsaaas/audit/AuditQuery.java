package com.devopsaaas.audit;

import com.devopsaaas.shared.security.CurrentUser;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQuery {

    private static final Instant OPEN_START = Instant.EPOCH;
    private static final Instant OPEN_END = Instant.parse("9999-12-31T00:00:00Z");

    private final AuditEventRepository events;

    AuditQuery(AuditEventRepository events) {
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(CurrentUser user, AuditResourceType resourceType, UUID resourceId,
            UUID actorUserId, Instant from, Instant to, int page, int size) {
        return search(user, new Filter(resourceType, resourceId, actorUserId, null, null, null), from, to, page,
                size);
    }

    /**
     * RF-45, slice 8: by resource, user, tool, execution and period. "Who restarted demo-api?" is
     * {@code resourceType=SERVICE, resourceId=<service>, toolName=restartContainer}.
     */
    public record Filter(AuditResourceType resourceType, UUID resourceId, UUID actorUserId, String toolName,
            UUID agentExecutionId, UUID toolExecutionId) {
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(CurrentUser user, Filter filter, Instant from, Instant to, int page, int size) {
        return events.search(user.organizationId(), filter.resourceType(), filter.resourceId(), filter.actorUserId(),
                filter.toolName(), filter.agentExecutionId(), filter.toolExecutionId(),
                from != null ? from : OPEN_START, to != null ? to : OPEN_END,
                PageRequest.of(page, size));
    }
}
