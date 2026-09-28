package com.devopsaaas.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.tool.api.RiskLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The state machine of ADR-0006: only PENDING ever changes, and a decision records who and when. */
class ApprovalTest {

    private static final UUID APPROVER = UUID.randomUUID();

    @Test
    void aDecision_recordsAuthorTimeAndComment() {
        Approval approval = pending();

        approval.decide(true, APPROVER, "ok");

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getDecidedBy()).isEqualTo(APPROVER);
        assertThat(approval.getDecidedAt()).isNotNull();
        assertThat(approval.getDecisionComment()).isEqualTo("ok");
    }

    @Test
    void onlyAPendingApproval_changes() {
        Approval approved = pending();
        approved.decide(true, APPROVER, null);
        assertThatThrownBy(() -> approved.decide(false, APPROVER, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(approved::expire).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(approved::cancel).isInstanceOf(IllegalStateException.class);

        Approval expired = pending();
        expired.expire();
        assertThat(expired.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(expired.getDecidedBy()).isNull();
        assertThatThrownBy(() -> expired.decide(true, APPROVER, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theDeadline_isInclusive() {
        Approval approval = pending();

        assertThat(approval.isExpiredAt(approval.getExpiresAt().minusMillis(1))).isFalse();
        assertThat(approval.isExpiredAt(approval.getExpiresAt())).isTrue();
        assertThat(approval.getExpiresAt()).isEqualTo(approval.getCreatedAt().plus(Duration.ofMinutes(15)));
        assertThat(approval.isExpiredAt(Instant.now())).isFalse();
    }

    private static Approval pending() {
        return Approval.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hash", RiskLevel.HIGH_RISK,
                "Interrupts in-flight requests.", "trust me", Duration.ofMinutes(15));
    }
}
