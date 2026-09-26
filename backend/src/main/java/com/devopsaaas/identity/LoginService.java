package com.devopsaaas.identity;

import com.devopsaaas.shared.error.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password login. Unknown e-mail, wrong password and disabled user all produce the same response, and an
 * unknown e-mail still pays for a password hash comparison, so neither the body nor the timing reveals
 * which accounts exist (TM-B1-02).
 */
@Service
public class LoginService {

    private static final String INVALID_CREDENTIALS = "Invalid e-mail or password.";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final LoginThrottle throttle;
    private final MeterRegistry meters;
    private final String unknownUserHash;

    LoginService(AppUserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens,
            LoginThrottle throttle, MeterRegistry meters) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.throttle = throttle;
        this.meters = meters;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public record LoginResult(String accessToken, long expiresInSeconds) {

        @Override
        public String toString() {
            return "LoginResult[accessToken=<redacted>, expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    @Transactional
    public LoginResult login(String email, String password, String clientIp) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        Optional<Duration> blocked = throttle.blockedFor(normalizedEmail, clientIp);
        if (blocked.isPresent()) {
            count("throttled");
            long seconds = Math.max(1, blocked.get().toSeconds());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed login attempts. Try again later.",
                    Map.of(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
        }

        Optional<AppUser> user = users.findForAuthenticationByEmail(normalizedEmail);
        String hash = user.map(AppUser::getPasswordHash).orElse(unknownUserHash);
        boolean passwordMatches = passwordEncoder.matches(password, hash);

        if (user.isEmpty() || !passwordMatches || !user.get().isActive()) {
            throttle.recordFailure(normalizedEmail, clientIp);
            count("failure");
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        }

        throttle.recordSuccess(normalizedEmail);
        user.get().recordLogin();
        count("success");
        return new LoginResult(tokens.issueFor(user.get().getId()), tokens.ttlSeconds());
    }

    private void count(String outcome) {
        meters.counter("devops.auth.login", "outcome", outcome).increment();
    }
}
