package com.devopsaaas.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** RNF-SEG-17 / TM-B1-02. */
class LoginThrottleTest {

    private final AtomicLong nanos = new AtomicLong();
    private final LoginThrottle throttle =
            new LoginThrottle(new LoginThrottleProperties(3, 5, Duration.ofMinutes(15), 1000), nanos::get);

    @Test
    void email_isBlocked_afterTheLimit_forTheRestOfTheWindow() {
        fail("alice@test.local", "10.0.0.1", 3);

        assertThat(throttle.blockedFor("alice@test.local", "10.0.0.2")).contains(Duration.ofMinutes(15));

        advance(Duration.ofMinutes(10));
        assertThat(throttle.blockedFor("alice@test.local", "10.0.0.2")).contains(Duration.ofMinutes(5));
    }

    @Test
    void email_isUnblocked_whenTheWindowExpires() {
        fail("alice@test.local", "10.0.0.1", 3);

        advance(Duration.ofMinutes(15).plusSeconds(1));

        assertThat(throttle.blockedFor("alice@test.local", "10.0.0.1")).isEmpty();
    }

    @Test
    void ip_isBlocked_afterTheLimit_acrossDifferentEmails() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("user" + i + "@test.local", "10.0.0.9");
        }

        assertThat(throttle.blockedFor("someone-else@test.local", "10.0.0.9")).isPresent();
        assertThat(throttle.blockedFor("someone-else@test.local", "10.0.0.10")).isEmpty();
    }

    @Test
    void success_clearsTheEmailCounter() {
        fail("alice@test.local", "10.0.0.1", 2);

        throttle.recordSuccess("alice@test.local");
        fail("alice@test.local", "10.0.0.1", 2);

        assertThat(throttle.blockedFor("alice@test.local", "10.0.0.3")).isEmpty();
    }

    @Test
    void belowTheLimit_nothingIsBlocked() {
        fail("alice@test.local", "10.0.0.1", 2);

        assertThat(throttle.blockedFor("alice@test.local", "10.0.0.1")).isEmpty();
    }

    private void fail(String email, String ip, int times) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure(email, ip);
        }
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
