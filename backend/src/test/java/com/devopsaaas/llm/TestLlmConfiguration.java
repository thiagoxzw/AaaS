package com.devopsaaas.llm;

import com.devopsaaas.llm.scripted.ScriptedLlmGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestLlmConfiguration {

    /** Only with the scripted provider; tests of the OpenAI adapter talk to a stub HTTP server instead. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "devops.llm.provider", havingValue = "scripted", matchIfMissing = true)
    InterceptingLlmGateway interceptingLlmGateway(ScriptedLlmGateway scripted) {
        return new InterceptingLlmGateway(scripted);
    }
}
