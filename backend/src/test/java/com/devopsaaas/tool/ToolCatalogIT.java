package com.devopsaaas.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.identity.Role;
import com.devopsaaas.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** The registry holds the full catalog; the endpoint shows only what the caller may use in that environment. */
class ToolCatalogIT extends IntegrationTest {

    @Test
    void operator_seesReadOnlyAndRiskyTools_andWhichOnesNeedApproval() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String environment = createEnvironment(admin, uniqueName("catalog")).get("id").asString();

        JsonNode tools = read(get("/api/v1/tools?environmentId=" + environment,
                createUser(DEFAULT_ORGANIZATION, Role.OPERATOR).token()));

        assertThat(names(tools)).contains("testStatus", "testRestart", "testSlowWrite");
        JsonNode restart = find(tools, "testRestart");
        assertThat(restart.get("riskLevel").asString()).isEqualTo("HIGH_RISK");
        assertThat(restart.get("requiresApproval").asBoolean()).isTrue();
        assertThat(find(tools, "testStatus").get("requiresApproval").asBoolean()).isFalse();
    }

    @Test
    void inputSchema_isPublishedFromTheInputRecord() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String environment = createEnvironment(admin, uniqueName("schema")).get("id").asString();

        JsonNode schema = find(read(get("/api/v1/tools?environmentId=" + environment, admin.token())), "testStatus")
                .get("inputSchema");

        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.get("required").toString()).contains("service");
        assertThat(schema.get("properties").get("service").get("pattern").asString())
                .isEqualTo("^[a-z0-9][a-z0-9-]{0,62}$");
    }

    @Test
    void viewer_seesNoTool_becauseEveryTestToolNeedsAgentPermissions() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String environment = createEnvironment(admin, uniqueName("viewer")).get("id").asString();

        JsonNode tools = read(get("/api/v1/tools?environmentId=" + environment,
                createUser(DEFAULT_ORGANIZATION, Role.VIEWER).token()));

        assertThat(tools.size()).isZero();
    }

    @Test
    void observeOnlyEnvironment_showsReadOnlyToolsOnly() {
        TestUser admin = createAdmin(DEFAULT_ORGANIZATION);
        String environment = createEnvironment(admin, uniqueName("observe"), "OBSERVE_ONLY").get("id").asString();

        JsonNode tools = read(get("/api/v1/tools?environmentId=" + environment, admin.token()));

        assertThat(names(tools)).contains("testStatus").doesNotContain("testRestart", "testSlowWrite");
        tools.valueStream().forEach(tool -> assertThat(tool.get("riskLevel").asString()).isEqualTo("READ_ONLY"));
    }

    @Test
    void anotherOrganizationsEnvironment_isNotFound() {
        String environmentOfB = createEnvironment(createAdmin(createOrganization()), uniqueName("b")).get("id").asString();

        assertThat(get("/api/v1/tools?environmentId=" + environmentOfB, createAdmin(DEFAULT_ORGANIZATION).token())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static List<String> names(JsonNode tools) {
        return tools.valueStream().map(tool -> tool.get("name").asString()).toList();
    }

    private static JsonNode find(JsonNode tools, String name) {
        return tools.valueStream().filter(tool -> tool.get("name").asString().equals(name)).findFirst().orElseThrow();
    }
}
