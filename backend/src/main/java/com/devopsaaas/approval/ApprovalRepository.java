package com.devopsaaas.approval;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface ApprovalRepository extends Repository<Approval, UUID> {

    Approval save(Approval approval);

    Optional<Approval> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Every decision, expiration and cancellation goes through this lock: two decisions on the same approval
     * are serialized, and the second one sees the first one's result (replay and double click, TM-B7-03).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Approval a where a.id = :id and a.organizationId = :organizationId")
    Optional<Approval> lockByIdAndOrganizationId(@Param("id") UUID id, @Param("organizationId") UUID organizationId);

    List<Approval> findAllByOrganizationIdAndStatusOrderByCreatedAtDesc(UUID organizationId, ApprovalStatus status);

    List<Approval> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<Approval> findAllByAgentExecutionIdAndOrganizationIdOrderByCreatedAtAsc(UUID agentExecutionId,
            UUID organizationId);

    long countByAgentExecutionIdAndOrganizationIdAndStatus(UUID agentExecutionId, UUID organizationId,
            ApprovalStatus status);

    /** Across organizations on purpose: only the expiration sweep uses it, as the system. */
    @Query("select a.id, a.organizationId from Approval a where a.status = 'PENDING' and a.expiresAt <= :now")
    List<Object[]> findDue(@Param("now") Instant now);
}
