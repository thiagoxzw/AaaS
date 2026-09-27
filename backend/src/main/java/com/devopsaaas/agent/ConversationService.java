package com.devopsaaas.agent;

import com.devopsaaas.audit.AuditAction;
import com.devopsaaas.audit.AuditEntry;
import com.devopsaaas.audit.AuditRecorder;
import com.devopsaaas.audit.AuditResourceType;
import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.environment.EnvironmentDirectory.ActiveEnvironment;
import com.devopsaaas.llm.LlmGateway;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conversations and the messages that start executions (RF-20..22, RF-27). Sending a message never waits
 * for the agent: it writes the message and a QUEUED execution in one transaction and answers 202.
 */
@Service
public class ConversationService {

    private static final String RETRY_AFTER_SECONDS = "5";

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final AgentExecutionRepository executions;
    private final EnvironmentDirectory environments;
    private final ContextBuilder context;
    private final ExecutionDispatcher dispatcher;
    private final LlmGateway llm;
    private final AgentProperties properties;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;

    ConversationService(ConversationRepository conversations, MessageRepository messages,
            AgentExecutionRepository executions, EnvironmentDirectory environments, ContextBuilder context,
            ExecutionDispatcher dispatcher, LlmGateway llm, AgentProperties properties, AuditRecorder audit,
            TransactionTemplate transactions) {
        this.conversations = conversations;
        this.messages = messages;
        this.executions = executions;
        this.environments = environments;
        this.context = context;
        this.dispatcher = dispatcher;
        this.llm = llm;
        this.properties = properties;
        this.audit = audit;
        this.transactions = transactions;
    }

    /** {@code replayed}: the same Idempotency-Key and body were seen before, and nothing new was created. */
    public record Accepted(UUID executionId, UUID conversationId, AgentExecutionStatus status, boolean replayed) {
    }

    public Conversation open(CurrentUser user, UUID environmentId, String title) {
        return transactions.execute(status -> {
            environments.findActive(user.organizationId(), environmentId)
                    .orElseThrow(() -> ApiException.notFound("Environment not found."));
            Conversation conversation = conversations.save(
                    Conversation.open(user.organizationId(), environmentId, user.userId(), title));
            audit.record(AuditEntry.byUser(user, AuditAction.CONVERSATION_CREATED, AuditResourceType.CONVERSATION,
                    conversation.getId()).detail("environmentId", environmentId));
            return conversation;
        });
    }

    public Accepted send(CurrentUser user, UUID conversationId, String content, String idempotencyKey) {
        String requestHash = idempotencyKey == null ? null : sha256(conversationId + "\n" + content);
        Optional<Accepted> replay = replay(user, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExecutionDispatcher.Reservation reservation = dispatcher.tryReserve().orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "The agent is at capacity; try again shortly.",
                Map.of(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)));
        AgentExecution execution = null;
        try {
            execution = Objects.requireNonNull(transactions.execute(status -> accept(user, conversationId, content,
                    idempotencyKey, requestHash)));
        } catch (DataIntegrityViolationException raced) {
            // Lost a race on one of the invariants: the same key sent twice at once, or a second message while
            // an execution is active.
            return replay(user, idempotencyKey, requestHash).orElseThrow(() -> activeExecutionConflict());
        } finally {
            if (execution == null) {
                reservation.release();
            }
        }
        dispatcher.dispatch(reservation, new ExecutionRef(execution.getId(), execution.getOrganizationId()));
        return new Accepted(execution.getId(), conversationId, execution.getStatus(), false);
    }

    private AgentExecution accept(CurrentUser user, UUID conversationId, String content, String idempotencyKey,
            String requestHash) {
        Conversation conversation = conversations.findByIdAndOrganizationId(conversationId, user.organizationId())
                .orElseThrow(() -> ApiException.notFound("Conversation not found."));
        if (!conversation.getCreatedBy().equals(user.userId())) {
            // The agent acts on behalf of whoever sends the message; only the creator does, so "on whose
            // behalf" has one answer per conversation.
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the creator of the conversation can send messages.");
        }
        if (conversation.getStatus() != ConversationStatus.OPEN) {
            throw ApiException.conflict("The conversation is archived.");
        }
        ActiveEnvironment environment = environments.findActive(user.organizationId(), conversation.getEnvironmentId())
                .orElseThrow(() -> ApiException.conflict("The conversation's environment is disabled."));
        if (executions.existsByConversationIdAndOrganizationIdAndStatusIn(conversationId, user.organizationId(),
                AgentExecutionStatus.ACTIVE)) {
            throw activeExecutionConflict();
        }
        int seq = messages.maxSeq(conversationId, user.organizationId()) + 1;
        Message trigger = messages.save(Message.user(conversation, seq, content));
        String snapshot = context.snapshot(environment, Math.max(1, seq - properties.conversationWindow() + 1), seq);
        AgentExecution execution = executions.save(AgentExecution.queued(conversation, trigger, user.userId(),
                environment.autonomyLevel(), llm.model(), SystemPrompt.VERSION, snapshot,
                new AgentExecution.Limits(properties.maxToolCalls(), properties.maxLlmIterations(),
                        properties.maxActiveTime().toMillis()),
                idempotencyKey, requestHash));
        audit.record(AuditEntry.byUser(user, AuditAction.AGENT_EXECUTION_REQUESTED, AuditResourceType.EXECUTION,
                        execution.getId())
                .agentExecutionId(execution.getId())
                .detail("conversationId", conversationId));
        return execution;
    }

    /** RF-27: the same key and body return the same execution; the same key with another body is refused. */
    private Optional<Accepted> replay(CurrentUser user, String idempotencyKey, String requestHash) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return executions.findByRequestedByAndIdempotencyKey(user.userId(), idempotencyKey).map(existing -> {
            if (!MessageDigest.isEqual(existing.getIdempotencyRequestHash().getBytes(StandardCharsets.UTF_8),
                    requestHash.getBytes(StandardCharsets.UTF_8))) {
                throw ApiException.unprocessable("This Idempotency-Key was already used with a different request.");
            }
            return new Accepted(existing.getId(), existing.getConversationId(), existing.getStatus(), true);
        });
    }

    private static ApiException activeExecutionConflict() {
        return ApiException.conflict("The conversation already has an active execution.");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
