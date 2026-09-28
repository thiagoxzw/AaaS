package com.devopsaaas.approval;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.time.Timestamps;
import com.devopsaaas.tool.api.RiskLevel;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A human decision about one risky tool call (ADR-0006, docs/04-modelo-de-dados.md section 4.10). It is bound
 * to the call and to the hash of its exact arguments, expires, and can be decided once. Every change happens
 * under the row lock of {@link ApprovalRepository#lockByIdAndOrganizationId}.
 */
@Entity
@Table(name = "approval")
public class Approval {

    @Id
    private UUID id;

    private UUID organizationId;
    private UUID agentExecutionId;
    private UUID toolExecutionId;

    @Enumerated(EnumType.STRING)
    private ApprovalStatus status;

    private String argumentsHash;

    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;

    private String impactDescription;
    private String agentJustification;
    private Instant expiresAt;
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionComment;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    protected Approval() {
    }

    static Approval request(UUID organizationId, UUID agentExecutionId, UUID toolExecutionId, String argumentsHash,
            RiskLevel riskLevel, String impactDescription, String agentJustification, Duration ttl) {
        Approval approval = new Approval();
        approval.id = Ids.newId();
        approval.organizationId = organizationId;
        approval.agentExecutionId = agentExecutionId;
        approval.toolExecutionId = toolExecutionId;
        approval.status = ApprovalStatus.PENDING;
        approval.argumentsHash = argumentsHash;
        approval.riskLevel = riskLevel;
        approval.impactDescription = impactDescription;
        approval.agentJustification = agentJustification;
        approval.createdAt = Timestamps.now();
        approval.updatedAt = approval.createdAt;
        approval.expiresAt = approval.createdAt.plus(ttl);
        return approval;
    }

    boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }

    boolean isExpiredAt(Instant now) {
        return !Objects.requireNonNull(expiresAt, "expiresAt").isAfter(now);
    }

    void decide(boolean approve, UUID userId, String comment) {
        requirePending();
        status = approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
        decidedBy = userId;
        decidedAt = Timestamps.now();
        decisionComment = comment;
        updatedAt = decidedAt;
    }

    void expire() {
        requirePending();
        status = ApprovalStatus.EXPIRED;
        updatedAt = Timestamps.now();
    }

    void cancel() {
        requirePending();
        status = ApprovalStatus.CANCELLED;
        updatedAt = Timestamps.now();
    }

    private void requirePending() {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("Approval " + id + " is " + status + ", not PENDING");
        }
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

    public UUID getToolExecutionId() {
        return toolExecutionId;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public String getArgumentsHash() {
        return argumentsHash;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getImpactDescription() {
        return impactDescription;
    }

    public String getAgentJustification() {
        return agentJustification;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionComment() {
        return decisionComment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
