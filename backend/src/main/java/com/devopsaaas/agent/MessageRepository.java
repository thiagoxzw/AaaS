package com.devopsaaas.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface MessageRepository extends Repository<Message, UUID> {

    <S extends Message> S save(S message);

    Optional<Message> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("select coalesce(max(m.seq), 0) from Message m where m.conversationId = :conversationId "
            + "and m.organizationId = :organizationId")
    int maxSeq(@Param("conversationId") UUID conversationId, @Param("organizationId") UUID organizationId);

    List<Message> findAllByConversationIdAndOrganizationIdAndSeqBetweenOrderBySeqAsc(UUID conversationId,
            UUID organizationId, int fromSeq, int toSeq);

    Optional<Message> findByAgentExecutionIdAndOrganizationIdAndRole(UUID agentExecutionId, UUID organizationId,
            MessageRole role);
}
