package com.devopsaaas.tool.diagnostics;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiagnosticsProperties.class)
class DiagnosticsConfiguration {

    @Bean
    ContainerDiagnostics containerDiagnostics(DiagnosticsProperties properties) {
        return new ContainerDiagnostics(properties, Clock.systemUTC());
    }
}
