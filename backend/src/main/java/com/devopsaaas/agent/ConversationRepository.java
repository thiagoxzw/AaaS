package com.devopsaaas.agent;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface ConversationRepository extends Repository<Conversation, UUID> {

    <S extends Conversation> S save(S conversation);

    Optional<Conversation> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
