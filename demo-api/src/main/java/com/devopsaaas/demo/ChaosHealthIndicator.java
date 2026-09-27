package com.devopsaaas.demo;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Feeds the container healthcheck: DOWN after /chaos/unhealthy, no answer at all after /chaos/hang. */
@Component("chaos")
class ChaosHealthIndicator implements HealthIndicator {

    private static final long HANG_MILLIS = 60_000;

    private final ChaosState state;

    ChaosHealthIndicator(ChaosState state) {
        this.state = state;
    }

    @Override
    public Health health() {
        if (state.hanging()) {
            try {
                // Longer than the healthcheck timeout, so Docker counts a failed probe.
                Thread.sleep(HANG_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        return state.unhealthy()
                ? Health.down().withDetail("reason", "chaos: unhealthy").build()
                : Health.up().build();
    }
}
