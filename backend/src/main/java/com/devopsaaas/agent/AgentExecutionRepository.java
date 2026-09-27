package com.devopsaaas.agent;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface AgentExecutionRepository extends Repository<AgentExecution, UUID> {

    <S extends AgentExecution> S save(S execution);

    Optional<AgentExecution> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Every state change goes through this row lock, so the worker and a cancellation never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AgentExecution e where e.id = :id and e.organizationId = :organizationId")
    Optional<AgentExecution> lockByIdAndOrganizationId(@Param("id") UUID id,
            @Param("organizationId") UUID organizationId);

    Optional<AgentExecution> findByRequestedByAndIdempotencyKey(UUID requestedBy, String idempotencyKey);

    boolean existsByConversationIdAndOrganizationIdAndStatusIn(UUID conversationId, UUID organizationId,
            Collection<AgentExecutionStatus> statuses);

    /** Recent executions of an environment's conversations, for the operational context. */
    @Query("select e.id from AgentExecution e, Conversation c where c.id = e.conversationId "
            + "and c.organizationId = e.organizationId and e.organizationId = :organizationId "
            + "and c.environmentId = :environmentId and e.createdAt >= :since order by e.createdAt desc")
    List<UUID> recentIdsInEnvironment(@Param("organizationId") UUID organizationId,
            @Param("environmentId") UUID environmentId, @Param("since") Instant since, Limit limit);

    /** Across organizations on purpose: only the startup recovery uses it, as the system. */
    List<AgentExecution> findAllByStatusOrderByCreatedAtAsc(AgentExecutionStatus status);
}
