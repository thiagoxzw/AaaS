package com.devopsaaas.approval;

import java.util.UUID;

/**
 * Published inside the transaction that closed an approval (approved, rejected, expired). Whoever resumes the
 * execution listens after the commit; a lost event is harmless, because the sweep resumes from the state in
 * the database (slice 7, decision 8).
 */
public record ApprovalDecided(UUID organizationId, UUID agentExecutionId, UUID approvalId, ApprovalStatus status) {
}
