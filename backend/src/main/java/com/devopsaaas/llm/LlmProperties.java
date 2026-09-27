package com.devopsaaas.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param provider {@code scripted} (deterministic scripts, no API key) until slice 6 brings {@code openai}
 * @param timeout per model call
 * @param scriptsLocation where the scripted provider loads its scripts from
 */
@ConfigurationProperties("devops.llm")
public record LlmProperties(
        @DefaultValue("scripted") String provider,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("classpath*:llm-scripts/*.json") String scriptsLocation) {
}
