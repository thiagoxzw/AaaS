package com.devopsaaas.agent;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.time.Timestamps;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One model turn (doc 04, 4.8): the "declared reasoning" behind the proposals of that turn (RF-46), and the
 * tokens it used. Neither the prompt nor the provider's raw payload is stored.
 */
@Entity
@Immutable
@Table(name = "llm_call")
public class LlmCall {

    @Id
    private UUID id;

    private UUID organizationId;
    private UUID agentExecutionId;
    private int seq;
    private String model;

    @Enumerated(EnumType.STRING)
    private LlmCallOutcome finishReason;

    private String assistantText;
    private Integer inputTokens;
    private Integer outputTokens;
    private BigDecimal estimatedCostUsd;
    private int durationMs;
    private String errorCode;
    private Instant createdAt;

    protected LlmCall() {
    }

    static LlmCall record(UUID organizationId, UUID agentExecutionId, int seq, String model,
            LlmCallOutcome finishReason, String assistantText, Integer inputTokens, Integer outputTokens,
            BigDecimal estimatedCostUsd, int durationMs, String errorCode) {
        LlmCall call = new LlmCall();
        call.id = Ids.newId();
        call.organizationId = organizationId;
        call.agentExecutionId = agentExecutionId;
        call.seq = seq;
        call.model = model;
        call.finishReason = finishReason;
        call.assistantText = assistantText;
        call.inputTokens = inputTokens;
        call.outputTokens = outputTokens;
        // The provider's estimate from its configured price table (RNF-CUS-01); the scripted provider costs nothing.
        call.estimatedCostUsd = estimatedCostUsd;
        call.durationMs = durationMs;
        call.errorCode = errorCode;
        call.createdAt = Timestamps.now();
        return call;
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

    public String getModel() {
        return model;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public BigDecimal getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getSeq() {
        return seq;
    }

    public LlmCallOutcome getFinishReason() {
        return finishReason;
    }

    public String getAssistantText() {
        return assistantText;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
