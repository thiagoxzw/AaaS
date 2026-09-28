package com.devopsaaas.agent;

import com.devopsaaas.approval.ApprovalView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RF-46 and H3 in one answer (slice 8): for one recorded action, who asked and what, what the agent had
 * observed before, why it says it acted (untrusted), who approved it, when, and what came out. Every part
 * comes from a record: the tool call, the execution, the approval and the audit trail.
 */
public record ActionExplanation(
        UUID toolExecutionId,
        UUID agentExecutionId,
        UUID conversationId,
        Request request,
        Action action,
        List<Observation> observations,
        AgentClaims agentClaims,
        ApprovalView approval,
        Result result,
        List<AuditLine> audit) {

    public ActionExplanation {
        observations = List.copyOf(observations);
        audit = List.copyOf(audit);
    }

    /** The user's question that started the execution, and who asked it. */
    public record Request(UUID requestedBy, String question, Instant askedAt) {
    }

    /** {@code arguments} are the exact recorded arguments, the ones any approval was bound to. */
    public record Action(int seq, String tool, String target, String risk, Object arguments) {
    }

    /** An earlier call of the same execution and the deterministic findings it produced. */
    public record Observation(int seq, String tool, String target, String status, List<String> findings) {

        public Observation {
            findings = List.copyOf(findings);
        }
    }

    /** The model's own words for this call: never verified, never rendered. */
    public record AgentClaims(String rationale, boolean trusted) {
    }

    public record Result(String status, String denialReason, String errorCode, String errorMessage, Object output,
            Instant finishedAt, Long durationMs) {
    }

    /** Who did what, from the audit trail: the approver's e-mail is the label of their USER event. */
    public record AuditLine(Instant occurredAt, String action, String actorType, String actorLabel,
            String outcome) {
    }
}
