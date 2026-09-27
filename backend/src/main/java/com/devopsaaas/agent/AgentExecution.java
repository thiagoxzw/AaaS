package com.devopsaaas.agent;

import com.devopsaaas.environment.AutonomyLevel;
import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.time.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The aggregate root of one request to the agent (docs/03-arquitetura.md, section 6). The limits, the
 * autonomy level, the model, the prompt version and the context are snapshots taken when the execution is
 * accepted, for the audit trail; the policy itself always re-reads the current configuration.
 *
 * <p>Every state change happens under a row lock (see {@code ExecutionJournal}), and a terminal status is
 * final: the methods below refuse to leave it.
 */
@Entity
@Table(name = "agent_execution")
public class AgentExecution {

    @Id
    private UUID id;

    private UUID organizationId;
    private UUID conversationId;
    private UUID triggerMessageId;
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    private AgentExecutionStatus status;

    private String statusReason;

    @Enumerated(EnumType.STRING)
    private AutonomyLevel autonomyLevel;

    private String llmModel;
    private String promptVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String contextSnapshot;

    private int maxToolCalls;
    private int maxLlmIterations;
    private long maxActiveMs;
    private int toolCallCount;
    private int llmIterationCount;
    private long activeMs;
    private long inputTokens;
    private long outputTokens;
    private BigDecimal estimatedCostUsd;
    private String idempotencyKey;
    private String idempotencyRequestHash;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected AgentExecution() {
    }

    record Limits(int maxToolCalls, int maxLlmIterations, long maxActiveMs) {
    }

    static AgentExecution queued(Conversation conversation, Message trigger, UUID requestedBy,
            AutonomyLevel autonomyLevel, String llmModel, String promptVersion, String contextSnapshot,
            Limits limits, String idempotencyKey, String idempotencyRequestHash) {
        AgentExecution execution = new AgentExecution();
        execution.id = Ids.newId();
        execution.organizationId = conversation.getOrganizationId();
        execution.conversationId = conversation.getId();
        execution.triggerMessageId = trigger.getId();
        execution.requestedBy = requestedBy;
        execution.status = AgentExecutionStatus.QUEUED;
        execution.autonomyLevel = autonomyLevel;
        execution.llmModel = llmModel;
        execution.promptVersion = promptVersion;
        execution.contextSnapshot = contextSnapshot;
        execution.maxToolCalls = limits.maxToolCalls();
        execution.maxLlmIterations = limits.maxLlmIterations();
        execution.maxActiveMs = limits.maxActiveMs();
        execution.estimatedCostUsd = BigDecimal.ZERO;
        execution.idempotencyKey = idempotencyKey;
        execution.idempotencyRequestHash = idempotencyRequestHash;
        execution.createdAt = Timestamps.now();
        execution.updatedAt = execution.createdAt;
        return execution;
    }

    // ---- transitions ------------------------------------------------------------------------------------

    void start() {
        require(AgentExecutionStatus.QUEUED);
        status = AgentExecutionStatus.RUNNING;
        startedAt = Timestamps.now();
        updatedAt = startedAt;
    }

    void addActiveTime(long millis) {
        activeMs += Math.max(0, millis);
        updatedAt = Timestamps.now();
    }

    /** Returns the sequence number of the new LLM call. */
    int countLlmIteration(int input, int output) {
        llmIterationCount++;
        inputTokens += input;
        outputTokens += output;
        updatedAt = Timestamps.now();
        return llmIterationCount;
    }

    /**
     * Counts a proposal BEFORE the policy sees it, whatever the policy will decide: invalid or denied
     * proposals consume the budget too. Returns how many calls were already counted.
     */
    int countToolCall() {
        int before = toolCallCount;
        toolCallCount++;
        updatedAt = Timestamps.now();
        return before;
    }

    void waitForApproval() {
        require(AgentExecutionStatus.RUNNING);
        status = AgentExecutionStatus.WAITING_APPROVAL;
        updatedAt = Timestamps.now();
    }

    /** RUNNING → a terminal status. */
    void finish(AgentExecutionStatus terminal, String reason) {
        require(AgentExecutionStatus.RUNNING);
        if (terminal.isActive()) {
            throw new IllegalArgumentException(terminal + " is not a terminal status");
        }
        close(terminal, reason);
    }

    /** Any active status → CANCELLED. */
    void cancel(String reason) {
        if (!status.isActive()) {
            throw new IllegalStateException("Execution " + id + " is already " + status);
        }
        close(AgentExecutionStatus.CANCELLED, reason);
    }

    private void close(AgentExecutionStatus terminal, String reason) {
        status = terminal;
        statusReason = reason;
        finishedAt = Timestamps.now();
        updatedAt = finishedAt;
    }

    private void require(AgentExecutionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Execution " + id + " is " + status + ", expected " + expected);
        }
    }

    boolean llmIterationsExhausted() {
        return llmIterationCount >= maxLlmIterations;
    }

    boolean activeTimeExhausted() {
        return activeMs >= maxActiveMs;
    }

    long remainingActiveMs() {
        return Math.max(0, maxActiveMs - activeMs);
    }

    // ---- getters ----------------------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getTriggerMessageId() {
        return triggerMessageId;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public AgentExecutionStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getContextSnapshot() {
        return contextSnapshot;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public int getMaxLlmIterations() {
        return maxLlmIterations;
    }

    public long getMaxActiveMs() {
        return maxActiveMs;
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public int getLlmIterationCount() {
        return llmIterationCount;
    }

    public long getActiveMs() {
        return activeMs;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public BigDecimal getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getIdempotencyRequestHash() {
        return idempotencyRequestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
