package com.devopsaaas.tool.execution;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxConcurrentExecutions tool calls running at the same time across the application
 * @param maxRetries extra attempts for retryable (read-only) tools on transient failures (RNF-CONF-06)
 */
@ConfigurationProperties("devops.tools")
public record ToolExecutionProperties(
        @DefaultValue("16") int maxConcurrentExecutions,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("200ms") Duration initialBackoff) {
}
