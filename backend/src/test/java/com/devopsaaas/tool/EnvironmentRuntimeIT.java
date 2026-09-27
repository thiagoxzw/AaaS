package com.devopsaaas.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.identity.Role;
import com.devopsaaas.support.IntegrationTest;
import com.devopsaaas.tool.container.ContainerRuntimeException;
import com.devopsaaas.tool.container.ContainerState;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import com.devopsaaas.tool.container.HealthStatus;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** RF-12: connectivity check and live service status, for API users (not the agent). */
class EnvironmentRuntimeIT extends IntegrationTest {

    @Autowired
    FakeContainerRuntime runtime;

    @Test
    void connectivityCheck_reportsTheEngine_orWhyItIsUnreachable() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String reachable = environment(admin, uniqueName("ok"), "reachable-conn");
        String unreachable = environment(admin, uniqueName("down"), "unreachable-conn");
        runtime.makeUnreachable("unreachable-conn");

        JsonNode ok = read(post("/api/v1/environments/" + reachable + "/connectivity-check", admin.token(), null));
        JsonNode down = read(post("/api/v1/environments/" + unreachable + "/connectivity-check", admin.token(), null));

        assertThat(ok.get("reachable").asBoolean()).isTrue();
        assertThat(ok.get("engineVersion").asString()).isEqualTo("29.3.1");
        assertThat(down.get("reachable").asBoolean()).isFalse();
        assertThat(down.get("problem").asString()).isEqualTo("UNAVAILABLE");
        assertThat(runtime.calls()).contains("version:reachable-conn", "version:unreachable-conn");
    }

    @Test
    void connectivityCheck_requiresEnvironmentManagement() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String environment = environment(admin, uniqueName("perm"), "local");

        assertThat(post("/api/v1/environments/" + environment + "/connectivity-check",
                createUser(DEFAULT_ORGANIZATION, Role.OPERATOR).token(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void servicesStatus_showsStateAndHealthOfAllowlistedServicesOnly() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String environment = environment(admin, uniqueName("status"), "local");
        String container = uniqueName("demo-container");
        allowlistService(admin, environment, "demo-api", container);
        runtime.setState(container, ContainerState.RUNNING, HealthStatus.UNHEALTHY);

        ResponseEntity<String> response = get("/api/v1/environments/" + environment + "/services/status",
                createUser(DEFAULT_ORGANIZATION, Role.VIEWER).token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode services = read(response);
        assertThat(services.size()).isEqualTo(1);
        assertThat(services.get(0).get("service").asString()).isEqualTo("demo-api");
        assertThat(services.get(0).get("state").asString()).isEqualTo("RUNNING");
        assertThat(services.get(0).get("health").asString()).isEqualTo("UNHEALTHY");
        assertThat(response.getBody()).doesNotContain(container);
    }

    @Test
    void servicesStatus_whenTheRuntimeIsDown_is503() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String environment = environment(admin, uniqueName("down"), "local");
        String container = uniqueName("down-container");
        allowlistService(admin, environment, "demo-api", container);
        runtime.failWith(container, ContainerRuntimeException.Category.UNAVAILABLE);

        assertThat(get("/api/v1/environments/" + environment + "/services/status", admin.token()).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void anotherOrganizationsEnvironment_isNotFound_forBothEndpoints() {
        String environmentOfB = environment(createAdmin(createOrganization()), uniqueName("b"), "local");
        String adminOfA = createAdmin(DEFAULT_ORGANIZATION).token();

        assertThat(post("/api/v1/environments/" + environmentOfB + "/connectivity-check", adminOfA, null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/environments/" + environmentOfB + "/services/status", adminOfA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String environment(TestUser admin, String name, String connectionRef) {
        Map<String, Object> body = new HashMap<>(environmentBody(name));
        body.put("connectionRef", connectionRef);
        ResponseEntity<String> response = post("/api/v1/environments", admin.token(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response).get("id").asString();
    }
}
