package com.devopsaaas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    @Test
    void secretShorterThan32Bytes_isRejectedAtStartup() {
        assertThatThrownBy(() -> new JwtProperties("too-short", "devops-agent", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void missingSecret_isRejectedAtStartup() {
        assertThatThrownBy(() -> new JwtProperties(null, "devops-agent", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toString_neverContainsTheSecret() {
        String secret = "s".repeat(48);

        String text = new JwtProperties(secret, "devops-agent", Duration.ofMinutes(15)).toString();

        assertThat(text).doesNotContain(secret).contains("<redacted>");
    }
}
