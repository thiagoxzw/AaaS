package com.devopsaaas.integration.openai;

import com.devopsaaas.llm.LlmProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OpenAI adapter when {@code devops.llm.provider=openai}. A paid provider without a daily budget
 * (RNF-CUS-02, TM-B3-05) stops the application at startup.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "devops.llm.provider", havingValue = LlmProperties.OPENAI)
@EnableConfigurationProperties(OpenAiProperties.class)
class OpenAiConfiguration {

    @Bean(destroyMethod = "close")
    OpenAiLlmAdapter llmGateway(OpenAiProperties properties, LlmProperties llm, MeterRegistry meters) {
        if (llm.dailyBudgetUsd() == null) {
            throw new IllegalStateException("LLM_PROVIDER=openai requires LLM_DAILY_BUDGET_USD "
                    + "(devops.llm.daily-budget-usd)");
        }
        return new OpenAiLlmAdapter(properties, meters);
    }
}
