package com.devopsaaas.tool.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * RNF-SEG-07b: best-effort masking of secrets in third-party content.
 *
 * <p>The fixtures below are fake secrets on purpose. Lines a secret scanner would flag carry an inline
 * {@code gitleaks:allow} marker, so the exception is visible and limited to that exact line.
 */
class SecretRedactorTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "db password=s3cr3t-value failed          | db password=<redacted> failed",
            "PASSWORD: \"quoted secret\"               | PASSWORD: <redacted>",
            "api_key=abc123def456                      | api_key=<redacted>", // gitleaks:allow (fake fixture)
            "client_secret='xyz'                       | client_secret=<redacted>",
            "Authorization: Bearer abcdefghij123456789 | Authorization: Bearer <redacted>",
            "postgres://app:hunter2@db:5432/app        | postgres://app:<redacted>@db:5432/app",
            "key AKIAIOSFODNN7EXAMPLE used             | key <redacted:aws-access-key> used"
    })
    void masksKnownSecretShapes(String input, String expected) {
        assertThat(SecretRedactor.redact(input.strip()).text()).isEqualTo(expected.strip());
    }

    @org.junit.jupiter.api.Test
    void masksJwtsAndPrivateKeys_andCountsEachRedaction() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"; // gitleaks:allow (fake fixture)
        String key = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";

        SecretRedactor.Result result = SecretRedactor.redact("token " + jwt + " and " + key);

        assertThat(result.text()).isEqualTo("token <redacted:jwt> and <redacted:private-key>");
        assertThat(result.redactions()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET /api/v1/environments 200 12ms",
            "Connection pool exhausted: 10/10 in use",
            "password reset email sent to user 42",
            "The token bucket is empty"
    })
    void leavesOrdinaryLogLinesUntouched(String line) {
        SecretRedactor.Result result = SecretRedactor.redact(line);

        assertThat(result.text()).isEqualTo(line);
        assertThat(result.redactions()).isZero();
    }

    @org.junit.jupiter.api.Test
    void isIdempotent() {
        String once = SecretRedactor.redact("password=abc Bearer abcdefghijklmnop").text();

        assertThat(SecretRedactor.redact(once).text()).isEqualTo(once);
        assertThat(SecretRedactor.redact(once).redactions()).isZero();
    }
}
