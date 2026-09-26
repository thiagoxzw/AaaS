package com.devopsaaas.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Brute-force protection (RNF-SEG-17). The per-IP limit is higher than the per-e-mail one because several
 * people can share an address (NAT).
 */
@ConfigurationProperties("devops.security.login")
public record LoginThrottleProperties(
        @DefaultValue("5") int maxFailuresPerEmail,
        @DefaultValue("50") int maxFailuresPerIp,
        @DefaultValue("15m") Duration window,
        @DefaultValue("10000") long maxTrackedKeys) {
}
