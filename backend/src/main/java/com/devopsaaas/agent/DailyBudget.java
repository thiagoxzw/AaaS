package com.devopsaaas.agent;

import com.devopsaaas.llm.LlmProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * RNF-CUS-02: the estimated LLM spend of an organization per UTC day, from the recorded calls. Checked when a
 * message is accepted (refused with 429) and before every model turn (BUDGET_EXCEEDED), so a running execution
 * cannot go far past the limit.
 */
@Component
class DailyBudget {

    private final LlmCallRepository calls;
    private final LlmProperties properties;
    private final Clock clock = Clock.systemUTC();

    DailyBudget(LlmCallRepository calls, LlmProperties properties) {
        this.calls = calls;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    boolean exhausted(UUID organizationId) {
        BigDecimal budget = properties.dailyBudgetUsd();
        return budget != null && calls.spentSince(organizationId, startOfToday()).compareTo(budget) >= 0;
    }

    /** Seconds until the budget renews, for the Retry-After header. */
    long secondsUntilRenewal() {
        Instant now = Instant.now(clock);
        return Math.max(1, Duration.between(now, startOfToday().plus(Duration.ofDays(1))).toSeconds());
    }

    private Instant startOfToday() {
        return LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
