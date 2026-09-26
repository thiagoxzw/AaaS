package com.devopsaaas.environment;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** RF-10, RF-11, RF-13, RF-45, RNF-SEG-14, TM-B1-04/05. */
class EnvironmentApiIT extends IntegrationTest {

    @Test
    void createdEnvironment_canBeReadAndListed_andIsAudited() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String name = uniqueName("local");

        ResponseEntity<String> created = post("/api/v1/environments", admin.token(), environmentBody(name));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode environment = read(created);
        String id = environment.get("id").asString();
        assertThat(created.getHeaders().getLocation()).hasToString("/api/v1/environments/" + id);
        assertThat(environment.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(read(get("/api/v1/environments/" + id, admin.token())).get("name").asString()).isEqualTo(name);
        assertThat(get("/api/v1/environments", admin.token()).getBody()).contains(id);

        JsonNode event = singleAuditEvent(admin, "ENVIRONMENT", id);
        assertThat(event.get("action").asString()).isEqualTo("ENVIRONMENT_CREATED");
        assertThat(event.get("actorUserId").asString()).isEqualTo(admin.id().toString());
        assertThat(event.get("details").get("name").asString()).isEqualTo(name);
    }

    @Test
    void duplicateName_isRejectedWith409() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String name = uniqueName("dup");
        createEnvironment(admin, name);

        assertThat(post("/api/v1/environments", admin.token(), environmentBody(name)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void automatedAutonomy_isRejectedWith422() {
        Map<String, Object> body = new HashMap<>(environmentBody(uniqueName("auto")));
        body.put("autonomyLevel", "AUTOMATED");

        assertThat(post("/api/v1/environments", createAdmin(DEFAULT_ORGANIZATION).token(), body).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void unknownFields_suchAsOrganizationId_areRejected() {
        Map<String, Object> body = new HashMap<>(environmentBody(uniqueName("mass")));
        body.put("organizationId", createOrganization().toString());

        assertThat(post("/api/v1/environments", createAdmin(DEFAULT_ORGANIZATION).token(), body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void connectionRef_cannotBeAUrl() {
        Map<String, Object> body = new HashMap<>(environmentBody(uniqueName("url")));
        body.put("connectionRef", "tcp://user:password@docker-host:2375");

        ResponseEntity<String> response = post("/api/v1/environments", createAdmin(DEFAULT_ORGANIZATION).token(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(response).get("errors").toString()).contains("connectionRef");
    }

    @Test
    void autonomyChange_isAuditedAsItsOwnEvent_andBumpsTheVersion() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        JsonNode environment = createEnvironment(admin, uniqueName("autonomy"));
        String id = environment.get("id").asString();

        ResponseEntity<String> updated = patch("/api/v1/environments/" + id, admin.token(), Map.of(
                "autonomyLevel", "OBSERVE_ONLY", "tier", "PROD", "version", environment.get("version").asLong()));

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(updated).get("autonomyLevel").asString()).isEqualTo("OBSERVE_ONLY");
        assertThat(read(updated).get("version").asLong()).isGreaterThan(environment.get("version").asLong());
        List<String> actions = auditActions(admin, "ENVIRONMENT", id);
        assertThat(actions).contains("ENVIRONMENT_AUTONOMY_CHANGED", "ENVIRONMENT_UPDATED", "ENVIRONMENT_CREATED");
    }

    @Test
    void staleVersion_isRejectedWith409() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        JsonNode environment = createEnvironment(admin, uniqueName("stale"));
        String path = "/api/v1/environments/" + environment.get("id").asString();
        long version = environment.get("version").asLong();
        patch(path, admin.token(), Map.of("description", "first", "version", version));

        assertThat(patch(path, admin.token(), Map.of("description", "second", "version", version)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anotherOrganizationsEnvironment_isNotFound() {
        TestUser adminOfB = createAdmin(createOrganization());
        String environmentOfB = createEnvironment(adminOfB, uniqueName("tenant-b")).get("id").asString();
        TestUser adminOfA = createAdmin(DEFAULT_ORGANIZATION);
        String path = "/api/v1/environments/" + environmentOfB;

        assertThat(get(path, adminOfA.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch(path, adminOfA.token(), Map.of("description", "x", "version", 0)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(path + "/services", adminOfA.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(path + "/services", adminOfA.token(), serviceBody("x", "x")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/environments", adminOfA.token()).getBody()).doesNotContain(environmentOfB);
    }

    @Test
    void allowlistedService_canBeAddedListedAndDisabled_andEachStepIsAudited() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String environmentId = createEnvironment(admin, uniqueName("allow")).get("id").asString();
        String servicesPath = "/api/v1/environments/" + environmentId + "/services";

        ResponseEntity<String> added = post(servicesPath, admin.token(), serviceBody("demo-api", "aaas-demo-api-1"));

        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode service = read(added);
        String serviceId = service.get("id").asString();
        assertThat(service.get("enabled").asBoolean()).isTrue();
        assertThat(get(servicesPath, admin.token()).getBody()).contains("demo-api");

        ResponseEntity<String> disabled = patch(servicesPath + "/" + serviceId, admin.token(),
                Map.of("enabled", false, "version", service.get("version").asLong()));

        assertThat(read(disabled).get("enabled").asBoolean()).isFalse();
        assertThat(auditActions(admin, "SERVICE", serviceId)).containsExactly("SERVICE_DISABLED", "SERVICE_ALLOWLISTED");
    }

    @Test
    void allowlistedService_rejectsDuplicatesAndInvalidLogicalNames() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String servicesPath = "/api/v1/environments/" + createEnvironment(admin, uniqueName("names")).get("id").asString()
                + "/services";
        post(servicesPath, admin.token(), serviceBody("demo-api", "container-1"));

        assertThat(post(servicesPath, admin.token(), serviceBody("demo-api", "container-2")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(servicesPath, admin.token(), serviceBody("other", "container-1")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(servicesPath, admin.token(), serviceBody("Demo_API", "container-3")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static Map<String, Object> serviceBody(String name, String containerName) {
        return Map.of("name", name, "containerName", containerName, "description", "Test service");
    }

    private JsonNode singleAuditEvent(TestUser user, String resourceType, String resourceId) {
        JsonNode items = auditItems(user, resourceType, resourceId);
        assertThat(items.size()).isEqualTo(1);
        return items.get(0);
    }

    private List<String> auditActions(TestUser user, String resourceType, String resourceId) {
        JsonNode items = auditItems(user, resourceType, resourceId);
        return items.valueStream().map(item -> item.get("action").asString()).toList();
    }

    private JsonNode auditItems(TestUser user, String resourceType, String resourceId) {
        return read(get("/api/v1/audit-events?resourceType=" + resourceType + "&resourceId=" + resourceId,
                user.token())).get("items");
    }
}
