package com.devopsaaas.support;

import com.devopsaaas.identity.JwtTokenService;
import com.devopsaaas.identity.Role;
import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.tool.testing.TestToolsConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for integration tests: one PostgreSQL container for the whole run (same image as docker compose),
 * the real application on a random port, and helpers to create tenants, users and tokens.
 *
 * <p>Tools that exist only in tests ({@link TestToolsConfiguration}) are registered for every integration test.
 *
 * <p>Tests never clean the database (the audit trail cannot be deleted by design); they isolate themselves
 * with unique names and their own organizations instead.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "devops.security.jwt.secret=" + IntegrationTest.JWT_SECRET,
                "devops.bootstrap.admin-email=" + IntegrationTest.BOOTSTRAP_EMAIL,
                "devops.bootstrap.admin-password=" + IntegrationTest.BOOTSTRAP_PASSWORD
        })
@Import(TestToolsConfiguration.class)
public abstract class IntegrationTest {

    public static final String JWT_SECRET = "integration-test-secret-with-more-than-32-bytes";
    public static final String BOOTSTRAP_EMAIL = "bootstrap-admin@test.local";
    public static final String BOOTSTRAP_PASSWORD = "bootstrap-password-123";
    public static final UUID DEFAULT_ORGANIZATION = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    protected static final String PASSWORD = "correct-horse-battery-staple";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}")
    protected int apiPort;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService tokens;

    private final RestClient http = RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
            .build();

    public record TestUser(UUID id, UUID organizationId, String email, String token) {
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    protected UUID createOrganization() {
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO organization (id, name, slug, status) VALUES (?, ?, ?, 'ACTIVE')",
                id, "Org " + id, "org-" + id);
        return id;
    }

    protected TestUser createUser(UUID organizationId, Role... roles) {
        UUID id = Ids.newId();
        String email = "user-" + id + "@test.local";
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO app_user (id, organization_id, email, display_name, password_hash, status,
                                      created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                """, id, organizationId, email, "Test user", passwordEncoder.encode(PASSWORD),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        for (Role role : roles) {
            jdbc.update("INSERT INTO user_role (user_id, organization_id, role) VALUES (?, ?, ?)",
                    id, organizationId, role.name());
        }
        return new TestUser(id, organizationId, email, tokens.issueFor(id));
    }

    protected TestUser createAdmin(UUID organizationId) {
        return createUser(organizationId, Role.ADMIN);
    }

    protected static String uniqueName(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    /** Signs arbitrary claims with the given secret, to build forged or expired tokens. */
    protected static String signedToken(String secret, JwtClaimsSet claims) {
        return NimbusJwtEncoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256)
                .build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    // ---- HTTP -----------------------------------------------------------------------------------------

    protected ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    protected ResponseEntity<String> post(String path, String token, Object body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    protected ResponseEntity<String> patch(String path, String token, Object body) {
        return exchange(HttpMethod.PATCH, path, token, body);
    }

    protected ResponseEntity<String> exchange(HttpMethod method, String path, String token, Object body) {
        RestClient.RequestBodySpec request = http.method(method).uri("http://localhost:" + apiPort + path);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .body(body instanceof String raw ? raw : json.writeValueAsString(body));
        }
        return request.retrieve().toEntity(String.class);
    }

    protected JsonNode read(ResponseEntity<String> response) {
        return json.readTree(response.getBody());
    }

    // ---- common operations ----------------------------------------------------------------------------

    protected static Map<String, Object> environmentBody(String name) {
        return Map.of(
                "name", name,
                "description", "Test environment",
                "type", "DOCKER",
                "tier", "DEV",
                "autonomyLevel", "ASSISTED",
                "connectionRef", "local");
    }

    protected JsonNode createEnvironment(TestUser user, String name) {
        return createEnvironment(user, name, "ASSISTED");
    }

    protected JsonNode createEnvironment(TestUser user, String name, String autonomyLevel) {
        Map<String, Object> body = new java.util.HashMap<>(environmentBody(name));
        body.put("autonomyLevel", autonomyLevel);
        ResponseEntity<String> response = post("/api/v1/environments", user.token(), body);
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException("Environment creation failed: " + response);
        }
        return read(response);
    }

    protected JsonNode allowlistService(TestUser user, String environmentId, String name, String containerName) {
        ResponseEntity<String> response = post("/api/v1/environments/" + environmentId + "/services", user.token(),
                Map.of("name", name, "containerName", containerName));
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException("Allowlisting failed: " + response);
        }
        return read(response);
    }
}
