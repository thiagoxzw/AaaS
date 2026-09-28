package com.devopsaaas.integration.openai;

import com.devopsaaas.llm.LlmException;
import com.devopsaaas.llm.LlmException.Category;
import com.devopsaaas.llm.LlmFinishReason;
import com.devopsaaas.llm.LlmGateway;
import com.devopsaaas.llm.LlmMessage;
import com.devopsaaas.llm.LlmRequest;
import com.devopsaaas.llm.LlmResponse;
import com.devopsaaas.llm.LlmToolCall;
import com.devopsaaas.llm.LlmToolSpec;
import com.devopsaaas.llm.LlmUsage;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LlmGateway} over the OpenAI Responses API ({@code POST /responses}), with a plain HTTP client (ADR-0010,
 * slice 6). It only translates; it never runs a tool.
 *
 * <ul>
 *   <li>Stateless: {@code store: false}, and the whole history goes in every call, rebuilt from our records.
 *       Our database stays the source of truth; no {@code previous_response_id}.</li>
 *   <li>{@code strict: false} on the tools: the backend validates every argument strictly anyway.</li>
 *   <li>Up to {@code maxRetries} retries on 429 and 5xx, honouring Retry-After, within the call's timeout;
 *       no retry on other 4xx, nor on a 429 that means the account has no credit left.</li>
 *   <li>Error bodies are never read into messages or logs: they may echo the prompt. The only field ever read
 *       is {@code error.type} of a 429, compared with a fixed value.</li>
 * </ul>
 *
 * <p>The request and response formats follow the public documentation as found through third-party sources
 * while writing this (the official pages were not reachable); the stub tests pin down this reading, and the
 * first run against the real API confirms it.
 */
public final class OpenAiLlmAdapter implements LlmGateway, AutoCloseable {

    static final String PROVIDER = "openai";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);

    private final OpenAiProperties properties;
    private final MeterRegistry meters;
    private final HttpClient http;
    private final URI endpoint;
    private final JsonMapper json = JsonMapper.builder().build();

    OpenAiLlmAdapter(OpenAiProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = meters;
        this.endpoint = URI.create(properties.baseUrl().toString().replaceAll("/+$", "") + "/responses");
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        byte[] body = json.writeValueAsBytes(requestBody(request));
        Instant deadline = Instant.now().plus(request.timeout());
        int attempt = 0;
        while (true) {
            attempt++;
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw new LlmException(Category.TIMEOUT, "The model call ran out of time");
            }
            HttpResponse<byte[]> response;
            try {
                response = http.send(HttpRequest.newBuilder(endpoint)
                                .timeout(remaining)
                                .header("Authorization", "Bearer " + properties.apiKey())
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
            } catch (HttpTimeoutException exception) {
                throw new LlmException(Category.TIMEOUT, "The model did not answer in time", exception);
            } catch (IOException exception) {
                if (!retry(attempt, Duration.ZERO, deadline)) {
                    throw new LlmException(Category.UNAVAILABLE, "The model provider could not be reached", exception);
                }
                continue;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LlmException(Category.UNAVAILABLE, "Interrupted while waiting for the model");
            }
            int status = response.statusCode();
            if (status == 200) {
                return parse(response.body());
            }
            Category category = status == 429
                    ? quotaExhausted(response.body()) ? Category.QUOTA_EXHAUSTED : Category.RATE_LIMITED
                    : status >= 500 || status == 408 ? Category.UNAVAILABLE
                    : Category.REJECTED;
            if (category == Category.REJECTED || category == Category.QUOTA_EXHAUSTED
                    || !retry(attempt, retryAfter(response), deadline)) {
                throw new LlmException(category, "The model provider answered HTTP " + status);
            }
        }
    }

    // ---- request ----------------------------------------------------------------------------------------

    ObjectNode requestBody(LlmRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", properties.model());
        body.put("instructions", request.systemPrompt());
        body.put("max_output_tokens", request.maxOutputTokens());
        body.put("store", false);
        ArrayNode input = body.putArray("input");
        for (LlmMessage message : request.messages()) {
            switch (message) {
                case LlmMessage.User user -> input.addObject().put("role", "user").put("content", user.text());
                case LlmMessage.Assistant assistant -> {
                    if (assistant.text() != null && !assistant.text().isBlank()) {
                        input.addObject().put("role", "assistant").put("content", assistant.text());
                    }
                    for (LlmToolCall call : assistant.toolCalls()) {
                        input.addObject()
                                .put("type", "function_call")
                                .put("call_id", call.id())
                                .put("name", call.name())
                                .put("arguments", call.argumentsJson());
                    }
                }
                case LlmMessage.ToolResult result -> input.addObject()
                        .put("type", "function_call_output")
                        .put("call_id", result.toolCallId())
                        .put("output", result.content());
            }
        }
        ArrayNode tools = body.putArray("tools");
        for (LlmToolSpec tool : request.tools()) {
            ObjectNode spec = tools.addObject();
            spec.put("type", "function");
            spec.put("name", tool.name());
            spec.put("description", tool.description());
            spec.set("parameters", json.valueToTree(tool.inputSchema()));
            spec.put("strict", false);
        }
        return body;
    }

    // ---- response ---------------------------------------------------------------------------------------

    private LlmResponse parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException exception) {
            throw new LlmException(Category.INVALID_RESPONSE, "The model provider answered with invalid JSON");
        }
        String status = root.path("status").asString("");
        if ("failed".equals(status)) {
            throw new LlmException(Category.UNAVAILABLE, "The model provider reported a failed response");
        }
        boolean incomplete = "incomplete".equals(status);
        if (incomplete && !"max_output_tokens".equals(root.path("incomplete_details").path("reason").asString(""))) {
            throw new LlmException(Category.INVALID_RESPONSE, "The model response is incomplete");
        }
        if (!incomplete && !"completed".equals(status)) {
            throw new LlmException(Category.INVALID_RESPONSE, "Unexpected response status");
        }

        StringBuilder text = new StringBuilder();
        List<LlmToolCall> calls = new ArrayList<>();
        for (JsonNode item : root.path("output")) {
            switch (item.path("type").asString("")) {
                case "message" -> {
                    for (JsonNode part : item.path("content")) {
                        String type = part.path("type").asString("");
                        // A refusal is the model's answer too: it is shown, not hidden.
                        String value = "output_text".equals(type) ? part.path("text").asString(null)
                                : "refusal".equals(type) ? part.path("refusal").asString(null) : null;
                        if (value != null) {
                            text.append(text.isEmpty() ? "" : "\n").append(value);
                        }
                    }
                }
                case "function_call" -> {
                    String callId = item.path("call_id").asString("");
                    if (callId.isBlank()) {
                        throw new LlmException(Category.INVALID_RESPONSE, "A function call without call_id");
                    }
                    calls.add(new LlmToolCall(callId, item.path("name").asString(""),
                            item.path("arguments").asString("")));
                }
                default -> {
                    // Reasoning items and other types are not part of the neutral contract.
                }
            }
        }
        LlmUsage usage = new LlmUsage(root.path("usage").path("input_tokens").asInt(0),
                root.path("usage").path("output_tokens").asInt(0));
        LlmFinishReason finish = incomplete ? LlmFinishReason.LENGTH
                : calls.isEmpty() ? LlmFinishReason.STOP : LlmFinishReason.TOOL_CALLS;
        return new LlmResponse(text.isEmpty() ? null : text.toString(), calls, finish, usage, cost(usage));
    }

    /** RNF-CUS-01: tokens times the configured prices. Cached-input discounts are ignored, so it errs high. */
    BigDecimal cost(LlmUsage usage) {
        return properties.inputPricePerMillionUsd().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(properties.outputPricePerMillionUsd().multiply(BigDecimal.valueOf(usage.outputTokens())))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    // ---- retries ----------------------------------------------------------------------------------------

    private boolean retry(int attempt, Duration retryAfter, Instant deadline) {
        if (attempt > properties.maxRetries()) {
            return false;
        }
        Duration backoff = INITIAL_BACKOFF.multipliedBy(1L << (attempt - 1));
        Duration wait = retryAfter.compareTo(backoff) > 0 ? retryAfter : backoff;
        if (wait.compareTo(properties.maxRetryAfter()) > 0) {
            wait = properties.maxRetryAfter();
        }
        if (Instant.now().plus(wait).isAfter(deadline)) {
            return false;
        }
        meters.counter("devops.llm.retries", "provider", PROVIDER).increment();
        try {
            Thread.sleep(wait);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * A 429 is either a real rate limit or an account without credit. Seen on the first real run (slice 6):
     * {@code {"error": {"type": "insufficient_quota", "code": "credit_balance_exhausted", ...}}}. Only
     * {@code error.type} is read and compared; nothing of the body reaches a message or a log.
     */
    private boolean quotaExhausted(byte[] body) {
        try {
            return "insufficient_quota".equals(json.readTree(body).path("error").path("type").asString(""));
        } catch (JacksonException exception) {
            return false;
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        try {
            return header.map(value -> Duration.ofSeconds(Long.parseLong(value.trim()))).orElse(Duration.ZERO);
        } catch (NumberFormatException exception) {
            // An HTTP date instead of seconds: fall back to the backoff.
            return Duration.ZERO;
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
