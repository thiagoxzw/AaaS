package com.devopsaaas.approval;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param ttl how long a human has to decide (RF-43)
 * @param sweepInterval how often expired approvals are closed and decided executions are resumed
 * @param sweepEnabled turned off in tests, which call the sweep directly
 */
@Validated
@ConfigurationProperties("devops.approval")
public record ApprovalProperties(
        @DefaultValue("15m") Duration ttl,
        @DefaultValue("30s") Duration sweepInterval,
        @DefaultValue("true") boolean sweepEnabled) {

    public ApprovalProperties {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("devops.approval.ttl must be positive");
        }
    }
}
