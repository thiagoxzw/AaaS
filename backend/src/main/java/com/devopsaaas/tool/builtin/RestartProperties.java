package com.devopsaaas.tool.builtin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * restartContainer (docs/05-contratos-das-ferramentas.md, 8.5).
 *
 * @param gracefulStop sent to Docker as {@code t}: how long the process gets after SIGTERM before SIGKILL
 * @param verificationWindow how long the tool watches the container after the restart
 * @param pollInterval how often it looks during that window
 * @param stableRunning without a healthcheck, how long the container must stay RUNNING to count as back, so a
 *        process that dies right after starting is not reported as a success
 */
@ConfigurationProperties("devops.tools.restart")
public record RestartProperties(
        @DefaultValue("10s") Duration gracefulStop,
        @DefaultValue("60s") Duration verificationWindow,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("5s") Duration stableRunning) {

    public RestartProperties {
        if (gracefulStop.isNegative() || gracefulStop.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("devops.tools.restart.graceful-stop must be between 0 and 30s");
        }
        if (!pollInterval.isPositive() || !verificationWindow.isPositive() || stableRunning.isNegative()) {
            throw new IllegalArgumentException("devops.tools.restart durations must be positive");
        }
    }
}
