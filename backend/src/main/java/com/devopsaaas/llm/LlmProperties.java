package com.devopsaaas.llm;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param provider {@code scripted} (deterministic scripts, no API key) or {@code openai}
 * @param timeout per model call, retries included
 * @param scriptsLocation where the scripted provider loads its scripts from
 * @param dailyBudgetUsd estimated spend per organization and UTC day after which new executions are refused
 *     (RNF-CUS-02); required with a paid provider, unset means no limit
 */
@ConfigurationProperties("devops.llm")
public record LlmProperties(
        @DefaultValue("scripted") String provider,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("classpath*:llm-scripts/*.json") String scriptsLocation,
        BigDecimal dailyBudgetUsd) {

    public static final String SCRIPTED = "scripted";
    public static final String OPENAI = "openai";
    private static final Set<String> PROVIDERS = Set.of(SCRIPTED, OPENAI);

    public LlmProperties {
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalStateException("devops.llm.provider (LLM_PROVIDER) must be one of " + PROVIDERS
                    + ", not '" + provider + "'");
        }
        if (dailyBudgetUsd != null && dailyBudgetUsd.signum() <= 0) {
            throw new IllegalStateException("devops.llm.daily-budget-usd (LLM_DAILY_BUDGET_USD) must be positive");
        }
    }
}
