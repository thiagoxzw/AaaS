package com.devopsaaas.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read side of the audit trail. Every query is scoped by organization; there is no unscoped finder.
 *
 * <p>The time range is always bound (see {@link AuditQuery}): PostgreSQL cannot infer the type of a null
 * timestamp parameter in an {@code :param is null} check.
 */
interface AuditEventRepository extends Repository<AuditEvent, UUID> {

    @Query("""
            select e from AuditEvent e
            where e.organizationId = :organizationId
              and (:resourceType is null or e.resourceType = :resourceType)
              and (:resourceId is null or e.resourceId = :resourceId)
              and (:actorUserId is null or e.actorUserId = :actorUserId)
              and (:toolName is null or e.toolName = :toolName)
              and (:agentExecutionId is null or e.agentExecutionId = :agentExecutionId)
              and (:toolExecutionId is null or e.toolExecutionId = :toolExecutionId)
              and e.occurredAt >= :from
              and e.occurredAt < :to
            order by e.occurredAt desc, e.id desc
            """)
    Page<AuditEvent> search(
            @Param("organizationId") UUID organizationId,
            @Param("resourceType") AuditResourceType resourceType,
            @Param("resourceId") UUID resourceId,
            @Param("actorUserId") UUID actorUserId,
            @Param("toolName") String toolName,
            @Param("agentExecutionId") UUID agentExecutionId,
            @Param("toolExecutionId") UUID toolExecutionId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
