package com.devopsaaas.llm;

import com.devopsaaas.llm.scripted.ScriptedLlmGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestLlmConfiguration {

    @Bean
    @Primary
    InterceptingLlmGateway interceptingLlmGateway(ScriptedLlmGateway scripted) {
        return new InterceptingLlmGateway(scripted);
    }
}
