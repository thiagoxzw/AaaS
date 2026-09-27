package com.devopsaaas.agent;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface LlmCallRepository extends Repository<LlmCall, UUID> {

    <S extends LlmCall> S save(S call);

    List<LlmCall> findAllByAgentExecutionIdAndOrganizationIdOrderBySeqAsc(UUID agentExecutionId,
            UUID organizationId);
}
