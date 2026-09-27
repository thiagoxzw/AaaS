package com.devopsaaas.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Helpers to talk to the agent through its API, as a client would. */
public abstract class AgentTestSupport extends IntegrationTest {

    private static final List<String> UNSETTLED = List.of("QUEUED", "RUNNING");

    private final RestClient http = RestClient.builder()
            .defaultStatusHandler(status -> status.isError(), (request, response) -> { })
            .build();

    protected String environment(TestUser admin, String autonomy, String containerName) {
        String environment = createEnvironment(admin, uniqueName("agent"), autonomy).get("id").asString();
        allowlistService(admin, environment, "demo-api", containerName);
        return environment;
    }

    protected String conversation(TestUser user, String environmentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("environmentId", environmentId);
        body.put("title", "test conversation");
        ResponseEntity<String> response = post("/api/v1/conversations", user.token(), body);
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException("Conversation creation failed: " + response);
        }
        return read(response).get("id").asString();
    }

    protected ResponseEntity<String> send(TestUser user, String conversationId, String content) {
        return send(user, conversationId, content, null);
    }

    protected ResponseEntity<String> send(TestUser user, String conversationId, String content,
            String idempotencyKey) {
        RestClient.RequestBodySpec request = http.method(HttpMethod.POST)
                .uri("http://localhost:" + apiPort + "/api/v1/conversations/" + conversationId + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(Map.of("content", content)));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.retrieve().toEntity(String.class);
    }

    /** Sends, expects 202, and waits until the execution leaves QUEUED/RUNNING. */
    protected JsonNode ask(TestUser user, String conversationId, String content) {
        ResponseEntity<String> accepted = send(user, conversationId, content);
        if (accepted.getStatusCode().value() != 202) {
            throw new IllegalStateException("Expected 202, got " + accepted);
        }
        return settled(user, read(accepted).get("executionId").asString());
    }

    protected JsonNode settled(TestUser user, String executionId) {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> read(get("/api/v1/executions/" + executionId, user.token())),
                        view -> !UNSETTLED.contains(view.get("status").asString()));
    }

    protected static List<JsonNode> actions(JsonNode execution) {
        return execution.get("actions").valueStream().toList();
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    protected static UUID id(JsonNode execution) {
        return UUID.fromString(execution.get("executionId").asString());
    }
}
