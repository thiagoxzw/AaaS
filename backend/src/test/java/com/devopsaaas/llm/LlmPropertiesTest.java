package com.devopsaaas.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LlmPropertiesTest {

    @Test
    void anUnknownProvider_stopsTheApplicationAtStartup() {
        assertThatThrownBy(() -> new LlmProperties("anthropic", Duration.ofSeconds(30), 1024, "x", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scripted").hasMessageContaining("openai");
    }

    @Test
    void aDailyBudget_mustBePositive() {
        assertThatThrownBy(() -> new LlmProperties("scripted", Duration.ofSeconds(30), 1024, "x", BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_DAILY_BUDGET_USD");
    }
}
