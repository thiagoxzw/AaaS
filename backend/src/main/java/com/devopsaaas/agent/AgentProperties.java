package com.devopsaaas.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits of the agent (RF-26, RNF-CONF-01..03), snapshotted on every execution, and the in-process dispatcher
 * (docs/03-arquitetura.md, section 8).
 *
 * @param maxActiveTime processing time only; waiting for an approval does not count
 * @param conversationWindow previous messages of the conversation sent to the model
 * @param workers executions running at the same time
 * @param queueCapacity accepted executions waiting for a worker; beyond it the API answers 503
 * @param recoverOnStartup mark crashed work and re-dispatch queued executions when the application starts
 */
@ConfigurationProperties("devops.agent")
public record AgentProperties(
        @DefaultValue("10") int maxToolCalls,
        @DefaultValue("8") int maxLlmIterations,
        @DefaultValue("5m") Duration maxActiveTime,
        @DefaultValue("10") int conversationWindow,
        @DefaultValue("4") int workers,
        @DefaultValue("50") int queueCapacity,
        @DefaultValue("true") boolean recoverOnStartup) {

    public AgentProperties {
        if (maxToolCalls < 1 || maxLlmIterations < 1 || workers < 1 || queueCapacity < 0 || conversationWindow < 1) {
            throw new IllegalStateException("devops.agent limits must be positive");
        }
    }
}
