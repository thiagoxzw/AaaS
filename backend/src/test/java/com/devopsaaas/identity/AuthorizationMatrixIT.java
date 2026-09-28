package com.devopsaaas.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every endpoint x every role: allowed exactly when the role's permissions include the one the endpoint
 * declares (TM-B1-08). Missing tokens are always 401. Since slice 9a the declared permission of EVERY handler
 * under /api/v1 is also compared with {@link #DECLARED}, so an endpoint added without an entry fails here.
 */
class AuthorizationMatrixIT extends IntegrationTest {

    @Autowired
    PermissionResolver permissionResolver;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlers;

    /** The specification: method and path of every API handler, and the only thing that authorizes it. */
    private static final Map<String, String> DECLARED = Map.ofEntries(
            Map.entry("POST /api/v1/auth/login", "public (SecurityConfiguration permitAll)"),
            Map.entry("GET /api/v1/me", "isAuthenticated()"),
            Map.entry("POST /api/v1/environments", "hasAuthority('ENVIRONMENT_MANAGE')"),
            Map.entry("GET /api/v1/environments", "hasAuthority('EXECUTION_READ')"),
            Map.entry("GET /api/v1/environments/{environmentId}", "hasAuthority('EXECUTION_READ')"),
            Map.entry("PATCH /api/v1/environments/{environmentId}", "hasAuthority('ENVIRONMENT_MANAGE')"),
            Map.entry("POST /api/v1/environments/{environmentId}/services", "hasAuthority('ENVIRONMENT_MANAGE')"),
            Map.entry("GET /api/v1/environments/{environmentId}/services", "hasAuthority('EXECUTION_READ')"),
            Map.entry("PATCH /api/v1/environments/{environmentId}/services/{serviceId}",
                    "hasAuthority('ENVIRONMENT_MANAGE')"),
            Map.entry("POST /api/v1/environments/{environmentId}/connectivity-check",
                    "hasAuthority('ENVIRONMENT_MANAGE')"),
            Map.entry("GET /api/v1/environments/{environmentId}/services/status", "hasAuthority('EXECUTION_READ')"),
            Map.entry("GET /api/v1/tools", "hasAuthority('EXECUTION_READ')"),
            Map.entry("POST /api/v1/conversations", "hasAuthority('AGENT_INTERACT')"),
            Map.entry("POST /api/v1/conversations/{conversationId}/messages", "hasAuthority('AGENT_INTERACT')"),
            Map.entry("GET /api/v1/executions/{executionId}", "hasAuthority('EXECUTION_READ')"),
            Map.entry("POST /api/v1/executions/{executionId}/cancel", "hasAuthority('AGENT_INTERACT')"),
            Map.entry("GET /api/v1/tool-executions/{toolExecutionId}", "hasAuthority('EXECUTION_READ')"),
            Map.entry("GET /api/v1/approvals", "hasAuthority('EXECUTION_READ')"),
            Map.entry("GET /api/v1/approvals/{approvalId}", "hasAuthority('EXECUTION_READ')"),
            Map.entry("POST /api/v1/approvals/{approvalId}/decision", "hasAuthority('APPROVAL_DECIDE')"),
            Map.entry("GET /api/v1/audit-events", "hasAuthority('AUDIT_READ')"));

    /** Slice 9a: the handlers the application really has, read from Spring MVC, against the specification. */
    @Test
    void everyApiHandler_declaresExactlyThePermissionOfTheSpecification() {
        Map<String, String> actual = new TreeMap<>();
        handlers.getHandlerMethods().forEach((info, method) -> {
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/")) {
                    continue;
                }
                PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(),
                        PreAuthorize.class);
                for (RequestMethod verb : info.getMethodsCondition().getMethods()) {
                    actual.put(verb + " " + path, annotation != null ? annotation.value()
                            : "public (SecurityConfiguration permitAll)");
                }
            }
        });

        assertThat(actual).isEqualTo(new TreeMap<>(DECLARED));
    }

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
