package com.devopsaaas.integration.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.llm.LlmException;
import com.devopsaaas.llm.LlmException.Category;
import com.devopsaaas.llm.LlmFinishReason;
import com.devopsaaas.llm.LlmMessage;
import com.devopsaaas.llm.LlmRequest;
import com.devopsaaas.llm.LlmResponse;
import com.devopsaaas.llm.LlmToolCall;
import com.devopsaaas.llm.LlmToolSpec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter against a stub of the Responses API. It pins down the request and response formats as read for
 * slice 6 (from third-party sources: the official pages were not reachable), until the first real run.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpenAiLlmAdapterTest {

    private static final String API_KEY = "sk-test-" + "k".repeat(40); // gitleaks:allow (fake fixture)

    private final JsonMapper json = JsonMapper.builder().build();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private OpenAiStub stub;
    private OpenAiLlmAdapter adapter;

    @BeforeEach
    void start() {
        stub = new OpenAiStub();
        adapter = adapter(2);
    }

    @AfterEach
    void stop() {
        adapter.close();
        stub.close();
    }

    private OpenAiLlmAdapter adapter(int maxRetries) {
        return new OpenAiLlmAdapter(new OpenAiProperties(API_KEY, "test-model", stub.baseUrl(),
                new BigDecimal("2.00"), new BigDecimal("8.00"), maxRetries, Duration.ofSeconds(1),
                Duration.ofSeconds(2)), meters);
    }

    // ---- request ----------------------------------------------------------------------------------------

    @Test
    void theRequest_isStateless_andCarriesTheWholeHistoryInTheResponsesFormat() {
        stub.answer(body -> OpenAiStub.Answer.ok(OpenAiStub.text("done", 10, 2)));

        adapter.complete(request(
                new LlmMessage.User("is demo-api up?"),
                new LlmMessage.Assistant("Checking.", List.of(new LlmToolCall("call_1", "getContainerStatus",
                        "{\"service\":\"demo-api\"}"))),
                new LlmMessage.ToolResult("call_1", "getContainerStatus", "{\"status\":\"SUCCEEDED\"}")));

        OpenAiStub.Recorded recorded = stub.requests().getFirst();
        assertThat(recorded.path()).isEqualTo("/v1/responses");
        assertThat(recorded.authorization()).isEqualTo("Bearer " + API_KEY);
        JsonNode body = json.readTree(recorded.body());
        assertThat(body.get("model").asString()).isEqualTo("test-model");
        assertThat(body.get("store").asBoolean()).isFalse();
        assertThat(body.has("previous_response_id")).isFalse();
        assertThat(body.get("instructions").asString()).isEqualTo("system prompt");
        assertThat(body.get("max_output_tokens").asInt()).isEqualTo(256);
        JsonNode input = body.get("input");
        assertThat(input.get(0).get("role").asString()).isEqualTo("user");
        assertThat(input.get(1).get("role").asString()).isEqualTo("assistant");
        assertThat(input.get(2).get("type").asString()).isEqualTo("function_call");
        assertThat(input.get(2).get("call_id").asString()).isEqualTo("call_1");
        assertThat(input.get(2).get("arguments").asString()).isEqualTo("{\"service\":\"demo-api\"}");
        assertThat(input.get(3).get("type").asString()).isEqualTo("function_call_output");
        assertThat(input.get(3).get("call_id").asString()).isEqualTo("call_1");
        JsonNode tool = body.get("tools").get(0);
        assertThat(tool.get("type").asString()).isEqualTo("function");
        assertThat(tool.get("name").asString()).isEqualTo("getContainerStatus");
        assertThat(tool.get("strict").asBoolean()).isFalse();
        assertThat(tool.get("parameters").get("additionalProperties").asBoolean()).isFalse();
        assertThat(recorded.body()).doesNotContain(API_KEY);
    }

    // ---- response ---------------------------------------------------------------------------------------

    @Test
    void functionCalls_becomeProposals_andReasoningItemsAreIgnored() {
        stub.answer(body -> OpenAiStub.Answer.ok(
                OpenAiStub.functionCall("call_9", "getContainerStatus", "{\"service\":\"demo-api\"}", 1000, 200)));

        LlmResponse response = adapter.complete(request(new LlmMessage.User("status?")));

        assertThat(response.finishReason()).isEqualTo(LlmFinishReason.TOOL_CALLS);
        assertThat(response.toolCalls()).containsExactly(
                new LlmToolCall("call_9", "getContainerStatus", "{\"service\":\"demo-api\"}"));
        assertThat(response.text()).isEqualTo("Checking.");
        assertThat(response.usage().inputTokens()).isEqualTo(1000);
        // 1000 × 2.00 / 1M + 200 × 8.00 / 1M
        assertThat(response.estimatedCostUsd()).isEqualByComparingTo("0.003600");
    }

    @Test
    void textIsTheAnswer_andAnIncompleteResponseIsLength() {
        stub.answer(body -> OpenAiStub.Answer.ok(OpenAiStub.text("demo-api is unhealthy", 5, 5)));
        assertThat(adapter.complete(request(new LlmMessage.User("q"))).finishReason())
                .isEqualTo(LlmFinishReason.STOP);

        stub.answer(body -> OpenAiStub.Answer.ok(OpenAiStub.truncated("The service looks")));
        LlmResponse truncated = adapter.complete(request(new LlmMessage.User("q")));
        assertThat(truncated.finishReason()).isEqualTo(LlmFinishReason.LENGTH);
        assertThat(truncated.text()).isEqualTo("The service looks");
    }

    @Test
    void aRefusal_isShownAsTheAnswer() {
        stub.answer(body -> OpenAiStub.Answer.ok("""
                {"status":"completed","output":[{"type":"message","role":"assistant",
                 "content":[{"type":"refusal","refusal":"I can't help with that."}]}],
                 "usage":{"input_tokens":1,"output_tokens":1}}"""));

        assertThat(adapter.complete(request(new LlmMessage.User("q"))).text()).isEqualTo("I can't help with that.");
    }

    @Test
    void unusableResponses_areInvalid() {
        for (String body : List.of("not json", "{\"status\":\"in_progress\",\"output\":[]}",
                "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},\"output\":[]}",
                "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"x\",\"arguments\":\"{}\"}]}")) {
            stub.answer(request -> OpenAiStub.Answer.ok(body));
            assertCategory(Category.INVALID_RESPONSE);
        }
        stub.answer(request -> OpenAiStub.Answer.ok("{\"status\":\"failed\",\"output\":[]}"));
        assertCategory(Category.UNAVAILABLE);
    }

    // ---- errors and retries -----------------------------------------------------------------------------

    @Test
    void a429_isRetried_honouringRetryAfter_thenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        stub.answer(body -> calls.incrementAndGet() == 1
                ? new OpenAiStub.Answer(429, "{}", Map.of("Retry-After", "1"), 0)
                : OpenAiStub.Answer.ok(OpenAiStub.text("ok", 1, 1)));

        long start = System.nanoTime();
        assertThat(adapter.complete(request(new LlmMessage.User("q"))).text()).isEqualTo("ok");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertThat(meters.get("devops.llm.retries").counter().count()).isEqualTo(1);
    }

    @Test
    void persistent429Or5xx_endAfterTheRetries_and4xxIsNeverRetried() {
        stub.answer(body -> OpenAiStub.Answer.status(429));
        assertCategory(Category.RATE_LIMITED);
        assertThat(stub.requests()).hasSize(3);

        stub.reset();
        stub.answer(body -> OpenAiStub.Answer.status(503));
        assertCategory(Category.UNAVAILABLE);
        assertThat(stub.requests()).hasSize(3);

        for (int status : List.of(400, 401, 403)) {
            stub.reset();
            stub.answer(body -> OpenAiStub.Answer.status(status));
            assertCategory(Category.REJECTED);
            assertThat(stub.requests()).as("HTTP " + status).hasSize(1);
        }
    }

    @Test
    void a429WithoutCredit_isQuotaExhausted_andNeverRetried() {
        stub.answer(body -> OpenAiStub.Answer.quotaExhausted());

        assertCategory(Category.QUOTA_EXHAUSTED);
        assertThat(stub.requests()).hasSize(1);
        assertThat(meters.find("devops.llm.retries").counter()).isNull();

        // Any other 429, or one whose body is not JSON, is still a rate limit and is retried.
        for (String body : List.of("{\"error\":{\"type\":\"requests\"}}", "not json", "")) {
            stub.reset();
            stub.answer(request -> new OpenAiStub.Answer(429, body, Map.of(), 0));
            assertCategory(Category.RATE_LIMITED);
            assertThat(stub.requests()).as(body).hasSize(3);
        }
    }

    @Test
    void aSlowProvider_timesOut_withinTheCallsTimeout() {
        stub.answer(body -> new OpenAiStub.Answer(200, OpenAiStub.text("late", 1, 1), Map.of(), 3_000));

        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter.complete(new LlmRequest("system prompt", List.of(new LlmMessage.User("q")),
                List.of(), 256, Duration.ofMillis(300))))
                .isInstanceOfSatisfying(LlmException.class,
                        exception -> assertThat(exception.category()).isEqualTo(Category.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void anUnreachableProvider_isUnavailable() {
        stub.close();

        assertCategory(Category.UNAVAILABLE);
    }

    /** TM-B3-02: the key is in the Authorization header only, never in a log line or an error message. */
    @Test
    void theApiKey_neverAppearsInLogsOrErrors(CapturedOutput output) {
        for (int status : List.of(401, 429, 500)) {
            stub.answer(body -> OpenAiStub.Answer.status(status));
            assertThatThrownBy(() -> adapter.complete(request(new LlmMessage.User("q"))))
                    .satisfies(error -> assertThat(error.getMessage()).doesNotContain(API_KEY)
                            .doesNotContain("echo of the prompt"));
        }
        stub.answer(body -> OpenAiStub.Answer.quotaExhausted());
        assertThatThrownBy(() -> adapter.complete(request(new LlmMessage.User("q"))))
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("echo of the prompt")
                        .doesNotContain("credit"));
        stub.answer(body -> OpenAiStub.Answer.ok("not json"));
        assertThatThrownBy(() -> adapter.complete(request(new LlmMessage.User("q"))));

        assertThat(output.getAll()).doesNotContain(API_KEY).doesNotContain("echo of the prompt");
        assertThat(new OpenAiProperties(API_KEY, "m", stub.baseUrl(), BigDecimal.ONE, BigDecimal.ONE, 2,
                Duration.ofSeconds(1), Duration.ofSeconds(1)).toString()).doesNotContain(API_KEY);
    }

    @Test
    void missingModelKeyOrPrices_stopTheApplication() {
        assertThatThrownBy(() -> new OpenAiProperties(" ", "m", stub.baseUrl(), BigDecimal.ONE, BigDecimal.ONE, 2,
                Duration.ofSeconds(1), Duration.ofSeconds(1))).hasMessageContaining("OPENAI_API_KEY");
        assertThatThrownBy(() -> new OpenAiProperties(API_KEY, null, stub.baseUrl(), BigDecimal.ONE, BigDecimal.ONE,
                2, Duration.ofSeconds(1), Duration.ofSeconds(1))).hasMessageContaining("LLM_MODEL");
        assertThatThrownBy(() -> new OpenAiProperties(API_KEY, "m", stub.baseUrl(), null, BigDecimal.ONE, 2,
                Duration.ofSeconds(1), Duration.ofSeconds(1))).hasMessageContaining("LLM_PRICE_INPUT");
        assertThatThrownBy(() -> new OpenAiProperties(API_KEY, "m", java.net.URI.create("http://api.example.com/v1"),
                BigDecimal.ONE, BigDecimal.ONE, 2, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .hasMessageContaining("https");
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private void assertCategory(Category category) {
        assertThatThrownBy(() -> adapter.complete(request(new LlmMessage.User("q"))))
                .isInstanceOfSatisfying(LlmException.class,
                        exception -> assertThat(exception.category()).isEqualTo(category));
    }

    private static LlmRequest request(LlmMessage... messages) {
        return new LlmRequest("system prompt", List.of(messages),
                List.of(new LlmToolSpec("getContainerStatus", "Get status", Map.of(
                        "type", "object", "additionalProperties", false,
                        "properties", Map.of("service", Map.of("type", "string")),
                        "required", List.of("service")))),
                256, Duration.ofSeconds(10));
    }
}
