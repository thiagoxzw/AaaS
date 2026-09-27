package com.devopsaaas.agent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface LlmCallRepository extends Repository<LlmCall, UUID> {

    <S extends LlmCall> S save(S call);

    List<LlmCall> findAllByAgentExecutionIdAndOrganizationIdOrderBySeqAsc(UUID agentExecutionId,
            UUID organizationId);

    /** Estimated spend of an organization since an instant (RNF-CUS-02). */
    @Query("select coalesce(sum(c.estimatedCostUsd), 0) from LlmCall c where c.organizationId = :organizationId "
            + "and c.createdAt >= :since")
    BigDecimal spentSince(@Param("organizationId") UUID organizationId, @Param("since") Instant since);
}
