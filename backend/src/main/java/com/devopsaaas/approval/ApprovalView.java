package com.devopsaaas.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * docs/05-contratos-das-ferramentas.md, section 13: what the system asserts ({@code system}, trusted) is kept
 * structurally apart from what the agent claims ({@code agentClaims}, untrusted, plain text), so no client can
 * present the model's justification as the system's assessment (TM-B7-06).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApprovalView(
        UUID approvalId,
        ApprovalStatus status,
        Instant createdAt,
        Instant expiresAt,
        UUID agentExecutionId,
        UUID toolExecutionId,
        Action action,
        SystemAssessment system,
        AgentClaims agentClaims,
        DecisionView decision) {

    /** The exact call that runs if approved: these arguments, and no other, are bound to the hash. */
    public record Action(String tool, String target, Object arguments) {
    }

    public record SystemAssessment(String riskLevel, String impact, String argumentsHash, List<Evidence> evidence) {

        public SystemAssessment {
            evidence = List.copyOf(evidence);
        }
    }

    /** A deterministic finding the backend computed earlier in the same execution. */
    public record Evidence(String code, String severity, String message, String source, Instant observedAt) {
    }

    /** The model's words: shown labelled as unverified, never rendered as HTML or Markdown. */
    public record AgentClaims(String justification, boolean trusted) {

        static AgentClaims of(String justification) {
            return new AgentClaims(justification, false);
        }
    }

    public record DecisionView(UUID decidedBy, Instant decidedAt, String comment) {
    }
}
