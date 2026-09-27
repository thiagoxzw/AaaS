package com.devopsaaas.tool.diagnostics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param restartLoopThreshold restarts by the restart policy from which a container is considered in a loop
 *     (Docker's RestartCount does not count manual restarts)
 * @param recentlyStartedWindow a container started less than this ago may still be settling
 */
@ConfigurationProperties("devops.diagnostics")
public record DiagnosticsProperties(
        @DefaultValue("3") int restartLoopThreshold,
        @DefaultValue("60s") Duration recentlyStartedWindow) {

    public DiagnosticsProperties {
        if (restartLoopThreshold < 1 || recentlyStartedWindow.isNegative()) {
            throw new IllegalStateException("devops.diagnostics limits must be positive");
        }
    }
}
