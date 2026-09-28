package com.devopsaaas.tool.execution;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.time.Timestamps;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.policy.DenialReason;
import com.devopsaaas.tool.policy.PolicyOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One tool call, from proposal to outcome (ADR-0009). Every proposal becomes a row, including denied ones,
 * so "proposed" and "executed" are the same records filtered by status.
 */
@Entity
@Table(name = "tool_execution")
public class ToolExecution {

    @Id
    private UUID id;

    private UUID organizationId;
    private UUID agentExecutionId;
    private UUID llmCallId;
    private int seq;
    private String llmToolCallId;
    private String toolName;
    private Integer toolVersion;

    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;

    private UUID targetServiceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String arguments;

    private String argumentsHash;
    private String rationale;

    @Enumerated(EnumType.STRING)
    private PolicyOutcome policyDecision;

    @Enumerated(EnumType.STRING)
    private DenialReason denialReason;

    @Enumerated(EnumType.STRING)
    private ToolExecutionStatus status;

    private int attemptCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String output;

    private boolean outputTruncated;
    private int redactionCount;

    @Enumerated(EnumType.STRING)
    private ToolErrorCode errorCode;

    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Long durationMs;
    private Instant updatedAt;

    @Version
    private Long version;

    protected ToolExecution() {
    }

    static ToolExecution proposed(UUID organizationId, UUID agentExecutionId, UUID llmCallId, int seq,
            String llmToolCallId, String toolName, Integer toolVersion, RiskLevel riskLevel, UUID targetServiceId,
            String arguments, String argumentsHash, String rationale, int argumentRedactions) {
        ToolExecution execution = new ToolExecution();
        execution.id = Ids.newId();
        execution.organizationId = organizationId;
        execution.agentExecutionId = agentExecutionId;
        execution.llmCallId = llmCallId;
        execution.seq = seq;
        execution.llmToolCallId = llmToolCallId;
        execution.toolName = toolName;
        execution.toolVersion = toolVersion;
        execution.riskLevel = riskLevel;
        execution.targetServiceId = targetServiceId;
        execution.arguments = arguments;
        execution.argumentsHash = argumentsHash;
        execution.rationale = rationale;
        execution.redactionCount = argumentRedactions;
        execution.status = ToolExecutionStatus.PROPOSED;
        execution.createdAt = Timestamps.now();
        execution.updatedAt = execution.createdAt;
        return execution;
    }

    void deny(DenialReason reason, String detail) {
        policyDecision = PolicyOutcome.DENY;
        denialReason = reason;
        errorMessage = detail;
        status = ToolExecutionStatus.DENIED;
        finishedAt = Timestamps.now();
        updatedAt = finishedAt;
    }

    void awaitApproval() {
        policyDecision = PolicyOutcome.REQUIRE_APPROVAL;
        status = ToolExecutionStatus.WAITING_APPROVAL;
        updatedAt = Timestamps.now();
    }

    /** Allowed by the policy but never started, because no execution slot freed up in time. */
    void rejectForCapacity(String message) {
        policyDecision = PolicyOutcome.ALLOW;
        status = ToolExecutionStatus.FAILED;
        errorCode = ToolErrorCode.CAPACITY_EXCEEDED;
        errorMessage = message;
        finishedAt = Timestamps.now();
        updatedAt = finishedAt;
    }

    /** Recorded and committed BEFORE the external call, so an interrupted call leaves evidence behind. */
    void start() {
        policyDecision = PolicyOutcome.ALLOW;
        status = ToolExecutionStatus.RUNNING;
        startedAt = Timestamps.now();
        updatedAt = startedAt;
    }

    void finish(ToolExecutionStatus finalStatus, int attempts, String outputJson, boolean truncated,
            int outputRedactions, ToolErrorCode code, String message) {
        status = finalStatus;
        attemptCount = attempts;
        output = outputJson;
        outputTruncated = truncated;
        redactionCount += outputRedactions;
        errorCode = code;
        errorMessage = message;
        finishedAt = Timestamps.now();
        updatedAt = finishedAt;
        durationMs = startedAt == null ? null : Duration.between(startedAt, finishedAt).toMillis();
    }

    /** A crash interrupted the call after it may have reached the runtime: the outcome is not known. */
    void markOutcomeUnknown(String message) {
        finish(ToolExecutionStatus.OUTCOME_UNKNOWN, attemptCount, null, false, 0, null, message);
    }

    /** Slice 7: a human approved the call and the policy allows it NOW; recorded before the external call. */
    void startApproved() {
        status = ToolExecutionStatus.RUNNING;
        startedAt = Timestamps.now();
        updatedAt = startedAt;
    }

    /** A human rejected the call: it never runs. */
    void reject() {
        status = ToolExecutionStatus.REJECTED;
        finishedAt = Timestamps.now();
        updatedAt = finishedAt;
    }

    /** Nobody decided in time: the call never runs. */
    void expire() {
        status = ToolExecutionStatus.EXPIRED;
        finishedAt = Timestamps.now();
        updatedAt = finishedAt;
    }

    /** A call waiting for approval whose execution was cancelled never runs. */
    void cancelWhileWaiting() {
        status = ToolExecutionStatus.CANCELLED;
        finishedAt = Timestamps.now();
        updatedAt = finishedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAgentExecutionId() {
        return agentExecutionId;
    }

    public UUID getLlmCallId() {
        return llmCallId;
    }

    public int getSeq() {
        return seq;
    }

    public String getLlmToolCallId() {
        return llmToolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public Integer getToolVersion() {
        return toolVersion;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public UUID getTargetServiceId() {
        return targetServiceId;
    }

    public String getArguments() {
        return arguments;
    }

    public String getArgumentsHash() {
        return argumentsHash;
    }

    public String getRationale() {
        return rationale;
    }

    public PolicyOutcome getPolicyDecision() {
        return policyDecision;
    }

    public DenialReason getDenialReason() {
        return denialReason;
    }

    public ToolExecutionStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getOutput() {
        return output;
    }

    public boolean isOutputTruncated() {
        return outputTruncated;
    }

    public int getRedactionCount() {
        return redactionCount;
    }

    public ToolErrorCode getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
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

    public Long getDurationMs() {
        return durationMs;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
