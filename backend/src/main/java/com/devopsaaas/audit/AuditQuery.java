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
        return events.search(user.organizationId(), resourceType, resourceId, actorUserId,
                from != null ? from : OPEN_START, to != null ? to : OPEN_END,
                PageRequest.of(page, size));
    }
}
