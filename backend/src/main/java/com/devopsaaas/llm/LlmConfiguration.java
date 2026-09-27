package com.devopsaaas.llm;

import com.devopsaaas.llm.scripted.ClasspathScriptRepository;
import com.devopsaaas.llm.scripted.ScriptRepository;
import com.devopsaaas.llm.scripted.ScriptedLlmGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * The scripted provider. The OpenAI adapter lives with the other adapters, in {@code integration.openai}, and
 * is wired there when {@code devops.llm.provider=openai}. An unknown provider stops the application at startup
 * ({@link LlmProperties}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
class LlmConfiguration {

    @Bean
    @ConditionalOnProperty(name = "devops.llm.provider", havingValue = LlmProperties.SCRIPTED, matchIfMissing = true)
    ScriptRepository scriptRepository(LlmProperties properties, ResourcePatternResolver resources) {
        return new ClasspathScriptRepository(resources, properties.scriptsLocation());
    }

    @Bean
    @ConditionalOnProperty(name = "devops.llm.provider", havingValue = LlmProperties.SCRIPTED, matchIfMissing = true)
    ScriptedLlmGateway llmGateway(ScriptRepository scripts) {
        return new ScriptedLlmGateway(scripts);
    }
}
