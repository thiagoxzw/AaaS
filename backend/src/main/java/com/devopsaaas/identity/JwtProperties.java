package com.devopsaaas.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT settings. The secret comes from the environment ({@code JWT_SECRET}) and must have at least 256 bits
 * for HS256; the application refuses to start otherwise.
 */
@ConfigurationProperties("devops.security.jwt")
public record JwtProperties(String secret, @DefaultValue("devops-agent") String issuer,
        @DefaultValue("15m") Duration ttl) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "devops.security.jwt.secret (JWT_SECRET) must be set and have at least 32 bytes");
        }
    }

    SecretKey secretKey() {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>, issuer=" + issuer + ", ttl=" + ttl + "]";
    }
}
