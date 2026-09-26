package com.devopsaaas.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.support.IntegrationTest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import tools.jackson.databind.JsonNode;

/** RF-01..03, RNF-SEG-08/13/17, TM-B1-01/02/03. */
class AuthenticationIT extends IntegrationTest {

    @Test
    void bootstrapAdmin_isCreatedFromConfiguration_andCanLogIn() {
        ResponseEntity<String> response = login(BOOTSTRAP_EMAIL, BOOTSTRAP_PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = read(response);
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
    }

    @Test
    void login_returnsBearerToken_thatIdentifiesTheUser() {
        TestUser user = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);

        String token = read(login(user.email(), PASSWORD)).get("accessToken").asString();
        JsonNode me = read(get("/api/v1/me", token));

        assertThat(me.get("userId").asString()).isEqualTo(user.id().toString());
        assertThat(me.get("organizationId").asString()).isEqualTo(DEFAULT_ORGANIZATION.toString());
        assertThat(me.get("permissions").toString()).contains("TOOL_OPERATE").doesNotContain("AUDIT_READ");
    }

    @Test
    void issuedToken_doesNotExposeAnythingDerivedFromTheSecret() {
        String token = createUser(DEFAULT_ORGANIZATION, Role.VIEWER).token();

        JsonNode header = json.readTree(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]));

        assertThat(header.get("alg").asString()).isEqualTo("HS256");
        assertThat(header.get("kid").asString()).isEqualTo(SecurityConfiguration.KEY_ID);
        assertThat(read(get("/api/v1/me", token)).get("userId")).isNotNull();
    }

    @Test
    void login_failsIdentically_forUnknownUserAndWrongPassword() {
        TestUser user = createUser(DEFAULT_ORGANIZATION, Role.VIEWER);

        ResponseEntity<String> wrongPassword = login(user.email(), "not-the-password");
        ResponseEntity<String> unknownUser = login("nobody-" + user.id() + "@test.local", "whatever-password");

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUser.getBody()).isEqualTo(wrongPassword.getBody());
    }

    @Test
    void login_isThrottled_afterFiveFailures_evenWithTheCorrectPassword() {
        TestUser user = createUser(DEFAULT_ORGANIZATION, Role.VIEWER);
        for (int i = 0; i < 5; i++) {
            assertThat(login(user.email(), "wrong-" + i).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> blocked = login(user.email(), PASSWORD);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    @Test
    void requestWithoutToken_isUnauthorized_withProblemJson() {
        ResponseEntity<String> response = get("/api/v1/environments", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json;charset=UTF-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(read(response).get("status").asInt()).isEqualTo(401);
    }

    @Test
    void tokenSignedWithAnotherKey_isRejected() {
        TestUser user = createUser(DEFAULT_ORGANIZATION, Role.ADMIN);
        String forged = signedToken("a-completely-different-secret-of-32-bytes!!", claims(user, Instant.now().plusSeconds(600)));

        assertThat(get("/api/v1/me", forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unsignedToken_isRejected() {
        TestUser user = createUser(DEFAULT_ORGANIZATION, Role.ADMIN);
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"iss\":\"devops-agent\",\"sub\":\"" + user.id() + "\",\"exp\":"
                + Instant.now().plusSeconds(600).getEpochSecond() + "}");

        assertThat(get("/api/v1/me", header + "." + payload + ".").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredToken_isRejected() {
        TestUser user = createUser(DEFAULT_ORGANIZATION, Role.ADMIN);
        String expired = signedToken(JWT_SECRET, claims(user, Instant.now().minusSeconds(3600)));

        assertThat(get("/api/v1/me", expired).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void disabledUser_isRejected_evenWithAValidToken() {
        TestUser user = createUser(DEFAULT_ORGANIZATION, Role.ADMIN);
        assertThat(get("/api/v1/me", user.token()).getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", user.id());

        assertThat(get("/api/v1/me", user.token()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void removedRole_takesEffectOnTheNextRequest() {
        TestUser user = createUser(DEFAULT_ORGANIZATION, Role.ADMIN);
        assertThat(post("/api/v1/environments", user.token(), environmentBody(uniqueName("role"))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        jdbc.update("DELETE FROM user_role WHERE user_id = ? AND role = 'ADMIN'", user.id());

        assertThat(post("/api/v1/environments", user.token(), environmentBody(uniqueName("role"))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<String> login(String email, String password) {
        return post("/api/v1/auth/login", null, Map.of("email", email, "password", password));
    }

    private static JwtClaimsSet claims(TestUser user, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer("devops-agent")
                .subject(user.id().toString())
                .issuedAt(expiresAt.minusSeconds(900))
                .expiresAt(expiresAt)
                .build();
    }

    private static String base64Url(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes());
    }
}
