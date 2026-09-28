package com.devopsaaas.approval;

import com.devopsaaas.audit.AuditAction;
import com.devopsaaas.audit.AuditEntry;
import com.devopsaaas.audit.AuditOutcome;
import com.devopsaaas.audit.AuditRecorder;
import com.devopsaaas.audit.AuditResourceType;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.shared.time.Timestamps;
import com.devopsaaas.tool.execution.ToolCallAwaitingApproval;
import com.devopsaaas.tool.execution.ToolExecutionHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The approval state machine (ADR-0006, slice 7). Each transition runs under the approval's row lock, changes
 * the tool call in the same transaction when it has to, and writes its audit event in that transaction too:
 * a decision without its audit record cannot exist (TM-B7-07).
 */
@Service
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalWorkflow {

    public enum Decision {
        APPROVE, REJECT
    }

    /** What happened to a decision request. */
    public sealed interface Outcome {
    }

    public record Decided(Approval approval) implements Outcome {
    }

    /** Someone decided already, or it expired or was cancelled: an approval is used once (RF-43). */
    public record NotPending(ApprovalStatus status) implements Outcome {
    }

    /** The deadline passed before the decision arrived; the approval is now EXPIRED. */
    public record ExpiredNow() implements Outcome {
    }

    /** A decided approval the agent must act on: the call, and the hash the human approved. */
    public record ApprovedCallRef(UUID toolExecutionId, String argumentsHash) {
    }

    private final ApprovalRepository approvals;
    private final ToolExecutionHistory tools;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final ApprovalProperties properties;

    ApprovalWorkflow(ApprovalRepository approvals, ToolExecutionHistory tools, AuditRecorder audit,
            ApplicationEventPublisher events, TransactionTemplate transactions, ApprovalProperties properties) {
        this.approvals = approvals;
        this.tools = tools;
        this.audit = audit;
        this.events = events;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** RF-41: in the transaction that recorded the call as WAITING_APPROVAL, so both exist or neither does. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onToolCallAwaitingApproval(ToolCallAwaitingApproval call) {
        Approval approval = approvals.save(Approval.request(call.organizationId(), call.agentExecutionId(),
                call.toolExecutionId(), call.argumentsHash(), call.riskLevel(), call.impactDescription(),
                call.agentJustification(), properties.ttl()));
        audit.record(AuditEntry.byAgentOnBehalfOf(call.organizationId(), call.requestedBy(),
                        AuditAction.APPROVAL_REQUESTED, AuditResourceType.APPROVAL, approval.getId())
                .toolName(call.toolName())
                .agentExecutionId(call.agentExecutionId())
                .toolExecutionId(call.toolExecutionId())
                .detail("riskLevel", call.riskLevel())
                .detail("argumentsHash", call.argumentsHash())
                .detail("expiresAt", approval.getExpiresAt().toString()));
    }

    /**
     * RF-42: approve or reject, by a user who holds APPROVAL_DECIDE now (checked by the endpoint against the
     * permissions reloaded for this request). Approving does not run anything here: the execution resumes after
     * the commit and runs exactly the recorded call.
     */
    public Outcome decide(CurrentUser user, UUID approvalId, Decision decision, String comment) {
        return transactions.execute(status -> {
            Approval approval = approvals.lockByIdAndOrganizationId(approvalId, user.organizationId())
                    .orElseThrow(() -> ApiException.notFound("Approval not found."));
            if (!approval.isPending()) {
                return new NotPending(approval.getStatus());
            }
            if (approval.isExpiredAt(Timestamps.now())) {
                expire(approval);
                return new ExpiredNow();
            }
            boolean approve = decision == Decision.APPROVE;
            approval.decide(approve, user.userId(), comment);
            if (!approve) {
                tools.closeWaiting(approval.getOrganizationId(), approval.getToolExecutionId(), true);
            }
            audit.record(AuditEntry.byUser(user, approve ? AuditAction.APPROVAL_GRANTED : AuditAction.APPROVAL_REJECTED,
                            AuditResourceType.APPROVAL, approval.getId())
                    .agentExecutionId(approval.getAgentExecutionId())
                    .toolExecutionId(approval.getToolExecutionId())
                    .detail("argumentsHash", approval.getArgumentsHash())
                    .detail("riskLevel", approval.getRiskLevel())
                    .detail("withComment", comment != null && !comment.isBlank()));
            publish(approval);
            return new Decided(approval);
        });
    }

    /**
     * RF-43, idempotent and driven by the stored state only: every approval still PENDING past its deadline
     * becomes EXPIRED, whoever notices it first (this sweep or a late decision).
     */
    public int expireDue() {
        int expired = 0;
        for (Object[] due : approvals.findDue(Timestamps.now())) {
            UUID approvalId = (UUID) due[0];
            UUID organizationId = (UUID) due[1];
            Boolean done = transactions.execute(status -> approvals.lockByIdAndOrganizationId(approvalId,
                            organizationId)
                    .filter(approval -> approval.isPending() && approval.isExpiredAt(Timestamps.now()))
                    .map(approval -> {
                        expire(approval);
                        return true;
                    })
                    .orElse(false));
            if (Boolean.TRUE.equals(done)) {
                expired++;
            }
        }
        return expired;
    }

    /** Within the caller's transaction: the approvals of a cancelled or interrupted execution. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelForExecution(UUID organizationId, UUID agentExecutionId) {
        int cancelled = 0;
        for (Approval found : approvals.findAllByAgentExecutionIdAndOrganizationIdOrderByCreatedAtAsc(
                agentExecutionId, organizationId)) {
            Approval approval = approvals.lockByIdAndOrganizationId(found.getId(), organizationId).orElseThrow();
            if (approval.isPending()) {
                approval.cancel();
                audit.record(AuditEntry.bySystem(organizationId, AuditAction.APPROVAL_CANCELLED,
                                AuditResourceType.APPROVAL, approval.getId())
                        .agentExecutionId(agentExecutionId)
                        .toolExecutionId(approval.getToolExecutionId()));
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Whether the execution still waits for a human. */
    @Transactional(readOnly = true)
    public boolean hasPending(UUID organizationId, UUID agentExecutionId) {
        return approvals.countByAgentExecutionIdAndOrganizationIdAndStatus(agentExecutionId, organizationId,
                ApprovalStatus.PENDING) > 0;
    }

    /** Approved calls of an execution; the executor runs each at most once, whoever asks. */
    @Transactional(readOnly = true)
    public List<ApprovedCallRef> approvedCalls(UUID organizationId, UUID agentExecutionId) {
        return approvals.findAllByAgentExecutionIdAndOrganizationIdOrderByCreatedAtAsc(agentExecutionId,
                        organizationId).stream()
                .filter(approval -> approval.getStatus() == ApprovalStatus.APPROVED)
                .map(approval -> new ApprovedCallRef(approval.getToolExecutionId(), approval.getArgumentsHash()))
                .toList();
    }

    private void expire(Approval approval) {
        approval.expire();
        tools.closeWaiting(approval.getOrganizationId(), approval.getToolExecutionId(), false);
        audit.record(AuditEntry.bySystem(approval.getOrganizationId(), AuditAction.APPROVAL_EXPIRED,
                        AuditResourceType.APPROVAL, approval.getId())
                .outcome(AuditOutcome.FAILURE)
                .agentExecutionId(approval.getAgentExecutionId())
                .toolExecutionId(approval.getToolExecutionId()));
        publish(approval);
    }

    private void publish(Approval approval) {
        events.publishEvent(new ApprovalDecided(approval.getOrganizationId(), approval.getAgentExecutionId(),
                approval.getId(), approval.getStatus()));
    }
}
