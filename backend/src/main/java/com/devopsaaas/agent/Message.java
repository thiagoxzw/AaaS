package com.devopsaaas.agent;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.time.Timestamps;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A user message or the agent's final answer. Messages never change once written. */
@Entity
@Immutable
@Table(name = "message")
public class Message {

    @Id
    private UUID id;

    private UUID organizationId;
    private UUID conversationId;
    private UUID agentExecutionId;
    private int seq;

    @Enumerated(EnumType.STRING)
    private MessageRole role;

    private String content;
    private Instant createdAt;

    protected Message() {
    }

    static Message user(Conversation conversation, int seq, String content) {
        return create(conversation.getOrganizationId(), conversation.getId(), null, seq, MessageRole.USER, content);
    }

    static Message assistant(UUID organizationId, UUID conversationId, UUID agentExecutionId, int seq,
            String content) {
        return create(organizationId, conversationId, agentExecutionId, seq, MessageRole.ASSISTANT, content);
    }

    private static Message create(UUID organizationId, UUID conversationId, UUID agentExecutionId, int seq,
            MessageRole role, String content) {
        Message message = new Message();
        message.id = Ids.newId();
        message.organizationId = organizationId;
        message.conversationId = conversationId;
        message.agentExecutionId = agentExecutionId;
        message.seq = seq;
        message.role = role;
        message.content = content;
        message.createdAt = Timestamps.now();
        return message;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getAgentExecutionId() {
        return agentExecutionId;
    }

    public int getSeq() {
        return seq;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
