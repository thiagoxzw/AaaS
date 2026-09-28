package com.devopsaaas.tool.execution;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface ToolExecutionRepository extends Repository<ToolExecution, UUID> {

    <S extends ToolExecution> S save(S execution);

    Optional<ToolExecution> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** Slice 7: every change of a call waiting for approval goes through this row lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ToolExecution t where t.id = :id and t.organizationId = :organizationId")
    Optional<ToolExecution> lockByIdAndOrganizationId(@Param("id") UUID id,
            @Param("organizationId") UUID organizationId);

    List<ToolExecution> findAllByAgentExecutionIdAndOrganizationIdOrderBySeqAsc(UUID agentExecutionId,
            UUID organizationId);

    List<ToolExecution> findAllByOrganizationIdAndAgentExecutionIdIn(UUID organizationId,
            Collection<UUID> agentExecutionIds);

    List<ToolExecution> findAllByAgentExecutionIdAndOrganizationIdAndStatus(UUID agentExecutionId,
            UUID organizationId, ToolExecutionStatus status);

    /**
     * Across organizations on purpose: only the startup recovery uses it, as the system, to close calls that
     * a crash left RUNNING (RNF-CONF-09).
     */
    List<ToolExecution> findAllByStatus(ToolExecutionStatus status);
}
