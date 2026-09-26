package com.devopsaaas.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Counts failed logins per e-mail and per client IP in a fixed window that starts at the first failure.
 * The cache is bounded, so a flood of random e-mails cannot exhaust memory. In-memory by design for a single
 * instance; a shared store (Redis) comes with multiple instances (ADR-0004).
 */
@Component
class LoginThrottle {

    private final LoginThrottleProperties properties;
    private final Cache<String, AtomicInteger> failures;

    @Autowired
    LoginThrottle(LoginThrottleProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    LoginThrottle(LoginThrottleProperties properties, Ticker ticker) {
        this.properties = properties;
        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(properties.window())
                .maximumSize(properties.maxTrackedKeys())
                .ticker(ticker)
                .build();
    }

    /** How long the caller must wait, if either the e-mail or the IP has reached its limit. */
    Optional<Duration> blockedFor(String email, String clientIp) {
        return remaining(emailKey(email), properties.maxFailuresPerEmail())
                .or(() -> remaining(ipKey(clientIp), properties.maxFailuresPerIp()));
    }

    void recordFailure(String email, String clientIp) {
        failures.get(emailKey(email), key -> new AtomicInteger()).incrementAndGet();
        failures.get(ipKey(clientIp), key -> new AtomicInteger()).incrementAndGet();
    }

    void recordSuccess(String email) {
        failures.invalidate(emailKey(email));
    }

    private Optional<Duration> remaining(String key, int limit) {
        AtomicInteger count = failures.getIfPresent(key);
        if (count == null || count.get() < limit) {
            return Optional.empty();
        }
        Duration age = failures.policy().expireAfterWrite()
                .flatMap(policy -> policy.ageOf(key))
                .orElse(Duration.ZERO);
        Duration left = properties.window().minus(age);
        return Optional.of(left.isNegative() ? Duration.ZERO : left);
    }

    private static String emailKey(String email) {
        return "email:" + email;
    }

    private static String ipKey(String clientIp) {
        return "ip:" + clientIp;
    }
}
