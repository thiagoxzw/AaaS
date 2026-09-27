package com.devopsaaas.integration.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.llm.LlmProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OpenAiConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmProperties.class)
    static class Base {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Base.class, OpenAiConfiguration.class)
            .withPropertyValues(
                    "devops.llm.openai.api-key=sk-config-test-key", // gitleaks:allow (fake fixture)
                    "devops.llm.openai.model=test-model",
                    "devops.llm.openai.input-price-per-million-usd=1",
                    "devops.llm.openai.output-price-per-million-usd=1");

    @Test
    void withTheScriptedProvider_theAdapterIsNotCreated() {
        runner.run(context -> assertThat(context).doesNotHaveBean(OpenAiLlmAdapter.class));
    }

    @Test
    void withOpenAi_butWithoutADailyBudget_theApplicationDoesNotStart() {
        runner.withPropertyValues("devops.llm.provider=openai")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("LLM_DAILY_BUDGET_USD"));
    }

    @Test
    void withOpenAi_butWithoutAModel_theApplicationDoesNotStart() {
        runner.withPropertyValues("devops.llm.provider=openai", "devops.llm.daily-budget-usd=5",
                        "devops.llm.openai.model=")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("LLM_MODEL"));
    }

    @Test
    void withEverythingConfigured_theAdapterIsTheGateway() {
        runner.withPropertyValues("devops.llm.provider=openai", "devops.llm.daily-budget-usd=5")
                .run(context -> assertThat(context).hasSingleBean(OpenAiLlmAdapter.class));
    }
}
