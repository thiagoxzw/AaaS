package com.devopsaaas.identity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.EnumSet;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator from environment variables when the database has no users, so no
 * credential is ever stored in a migration or in the repository.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String DEFAULT_ORGANIZATION = "default";
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapProperties properties;

    AdminBootstrap(AppUserRepository users, OrganizationRepository organizations, PasswordEncoder passwordEncoder,
            BootstrapProperties properties) {
        this.users = users;
        this.organizations = organizations;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    @SuppressFBWarnings(value = "CRLF_INJECTION_LOGS",
            justification = "Logs a generated UUID; logs are JSON-encoded, which escapes line breaks anyway")
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String email = properties.adminEmail();
        String password = properties.adminPassword();
        if (email == null || email.isBlank() || password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("No users exist yet: set ADMIN_EMAIL and ADMIN_PASSWORD "
                    + "(at least " + MIN_PASSWORD_LENGTH + " characters) to create the first administrator");
        }
        Organization organization = organizations.findBySlug(DEFAULT_ORGANIZATION)
                .orElseThrow(() -> new IllegalStateException("Default organization is missing (migration V5)"));
        AppUser admin = users.save(AppUser.create(organization.getId(), email.trim().toLowerCase(Locale.ROOT),
                "Administrator", passwordEncoder.encode(password), EnumSet.allOf(Role.class)));
        log.info("Created bootstrap administrator {}", admin.getId());
    }
}
