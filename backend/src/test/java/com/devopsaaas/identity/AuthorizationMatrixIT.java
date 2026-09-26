package com.devopsaaas.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.support.IntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Every endpoint x every role: allowed exactly when the role's permissions include the one the endpoint
 * declares (TM-B1-08). Missing tokens are always 401.
 */
class AuthorizationMatrixIT extends IntegrationTest {

    @Autowired
    PermissionResolver permissionResolver;

    record Endpoint(HttpMethod method, String path, Permission required, boolean hasBody) {
    }

    private List<Endpoint> endpoints(String environmentId) {
        return List.of(
                new Endpoint(HttpMethod.GET, "/api/v1/environments", Permission.EXECUTION_READ, false),
                new Endpoint(HttpMethod.POST, "/api/v1/environments", Permission.ENVIRONMENT_MANAGE, true),
                new Endpoint(HttpMethod.GET, "/api/v1/environments/" + environmentId, Permission.EXECUTION_READ, false),
                new Endpoint(HttpMethod.GET, "/api/v1/environments/" + environmentId + "/services",
                        Permission.EXECUTION_READ, false),
                new Endpoint(HttpMethod.GET, "/api/v1/audit-events", Permission.AUDIT_READ, false));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void eachEndpoint_allowsExactlyTheRolesWithItsPermission(Role role) {
        String environmentId = createEnvironment(createAdmin(DEFAULT_ORGANIZATION), uniqueName("matrix"))
                .get("id").asString();
        TestUser user = createUser(DEFAULT_ORGANIZATION, role);
        Set<Permission> granted = permissionResolver.permissionsFor(List.of(role));

        for (Endpoint endpoint : endpoints(environmentId)) {
            Object body = endpoint.hasBody() ? environmentBody(uniqueName("matrix")) : null;
            HttpStatusCode status = exchange(endpoint.method(), endpoint.path(), user.token(), body).getStatusCode();

            if (granted.contains(endpoint.required())) {
                assertThat(status.value()).as("%s %s as %s", endpoint.method(), endpoint.path(), role)
                        .isNotIn(401, 403);
            } else {
                assertThat(status).as("%s %s as %s", endpoint.method(), endpoint.path(), role)
                        .isEqualTo(HttpStatus.FORBIDDEN);
            }
        }
    }

    @Test
    void everyEndpoint_requiresAToken() {
        for (Endpoint endpoint : endpoints(java.util.UUID.randomUUID().toString())) {
            Object body = endpoint.hasBody() ? environmentBody(uniqueName("anon")) : null;
            assertThat(exchange(endpoint.method(), endpoint.path(), null, body).getStatusCode())
                    .as("%s %s", endpoint.method(), endpoint.path())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
