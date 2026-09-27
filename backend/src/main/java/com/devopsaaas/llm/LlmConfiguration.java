package com.devopsaaas.llm;

import com.devopsaaas.llm.scripted.ClasspathScriptRepository;
import com.devopsaaas.llm.scripted.ScriptRepository;
import com.devopsaaas.llm.scripted.ScriptedLlmGateway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Picks the provider from configuration. An unknown provider, or one that does not exist yet, stops the
 * application at startup instead of failing on the first message.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
class LlmConfiguration {

    static final String SCRIPTED = "scripted";

    @Bean
    ScriptRepository scriptRepository(LlmProperties properties, ResourcePatternResolver resources) {
        return new ClasspathScriptRepository(resources, properties.scriptsLocation());
    }

    @Bean
    ScriptedLlmGateway llmGateway(LlmProperties properties, ScriptRepository scripts) {
        if (!SCRIPTED.equals(properties.provider())) {
            throw new IllegalStateException("devops.llm.provider (LLM_PROVIDER) must be 'scripted'; '"
                    + properties.provider() + "' is not available yet (the OpenAI adapter arrives in slice 6)");
        }
        return new ScriptedLlmGateway(scripts);
    }
}
