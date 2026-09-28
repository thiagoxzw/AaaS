package com.devopsaaas.integration.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.identity.Role;
import com.devopsaaas.support.AgentTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The whole agent with {@code LLM_PROVIDER=openai}, the "model" being a stub of the Responses API: the real
 * adapter, the real loop, the real policy. No test ever calls OpenAI.
 */
@TestPropertySource(properties = {
        "devops.llm.provider=openai",
        "devops.llm.openai.api-key=" + OpenAiAgentIT.API_KEY,
        "devops.llm.openai.model=test-model",
        "devops.llm.openai.input-price-per-million-usd=2.00",
        "devops.llm.openai.output-price-per-million-usd=8.00",
        // Each stubbed turn costs 0.0036 USD: the second turn of an execution crosses this budget.
        "devops.llm.daily-budget-usd=0.005"
})
class OpenAiAgentIT extends AgentTestSupport {

    static final String API_KEY = "sk-it-qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"; // gitleaks:allow (fake fixture)

    static final OpenAiStub STUB = new OpenAiStub();

    static {
        STUB.answer(OpenAiAgentIT::answer);
    }

    /** A "model" that checks the status first and then answers; [LOOP] never stops; [FAIL] is an outage. */
    private static OpenAiStub.Answer answer(String body) {
        if (body.contains("[FAIL]")) {
            return OpenAiStub.Answer.status(500);
        }
        if (body.contains("[NO-CREDIT]")) {
            return OpenAiStub.Answer.quotaExhausted();
        }
        if (body.contains("function_call_output") && !body.contains("[LOOP]")) {
            return OpenAiStub.Answer.ok(OpenAiStub.text("demo-api is running; see the findings.", 1000, 200));
        }
        return OpenAiStub.Answer.ok(OpenAiStub.functionCall("call_" + UUID.randomUUID(), "getContainerStatus",
                "{\"service\":\"demo-api\"}", 1000, 200));
    }

    @DynamicPropertySource
    static void openAi(DynamicPropertyRegistry registry) {
        registry.add("devops.llm.openai.base-url", () -> STUB.baseUrl().toString());
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    private record Tenant(TestUser operator, String conversation) {
    }

    /** Budgets are per organization and day: every test gets its own organization. */
    private Tenant tenant() {
        UUID organization = createOrganization();
        TestUser admin = createAdmin(organization);
        TestUser operator = createUser(organization, Role.OPERATOR);
        String environment = environment(admin, "ASSISTED", uniqueName("openai-container"));
        return new Tenant(operator, conversation(operator, environment));
    }

    @Test
    void theOpenAiProvider_drivesTheSameLoop_andTheCostIsRecorded() {
        Tenant tenant = tenant();
        String marker = uniqueName("why");

        JsonNode execution = ask(tenant.operator(), tenant.conversation(), "why is my API down? " + marker);

        assertThat(execution.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(execution.get("llmModel").asString()).isEqualTo("test-model");
        assertThat(execution.get("answer").get("text").asString()).isEqualTo("demo-api is running; see the findings.");
        assertThat(actions(execution)).singleElement()
                .satisfies(action -> assertThat(action.get("status").asString()).isEqualTo("SUCCEEDED"));
        assertThat(execution.get("usage").get("inputTokens").asLong()).isEqualTo(2000);
        assertThat(execution.get("usage").get("outputTokens").asLong()).isEqualTo(400);
        assertThat(execution.get("usage").get("estimatedCostUsd").decimalValue()).isEqualByComparingTo("0.0072");
        assertThat(jdbc.queryForList("SELECT estimated_cost_usd FROM llm_call WHERE agent_execution_id = ? "
                + "ORDER BY seq", java.math.BigDecimal.class, id(execution)))
                .allSatisfy(cost -> assertThat(cost).isEqualByComparingTo("0.0036"));

        // The second turn carried the call and its result back, with the backend's findings.
        List<OpenAiStub.Recorded> requests = STUB.requests().stream()
                .filter(request -> request.body().contains(marker)).toList();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).body()).contains("\"type\":\"function_call\"")
                .contains("\"type\":\"function_call_output\"").contains("RECENTLY_STARTED");
        assertThat(requests).allSatisfy(request -> assertThat(request.authorization()).isEqualTo("Bearer " + API_KEY));
    }

    /** TM-B3-01, RNF-SEG-07a: the configured secrets never reach the provider. */
    @Test
    void theRequestsSentToTheProvider_neverContainTheSystemsSecrets() {
        Tenant tenant = tenant();
        String marker = uniqueName("secrets");

        ask(tenant.operator(), tenant.conversation(), "status please " + marker);

        List<OpenAiStub.Recorded> requests = STUB.requests().stream()
                .filter(request -> request.body().contains(marker)).toList();
        assertThat(requests).isNotEmpty().allSatisfy(request -> assertThat(request.body())
                .doesNotContain(API_KEY, JWT_SECRET, BOOTSTRAP_PASSWORD, PASSWORD, tenant.operator().token()));
    }

    /** RNF-CUS-02: once the organization's daily budget is spent, new executions are refused with 429. */
    @Test
    void anExhaustedDailyBudget_refusesNewMessages() {
        Tenant tenant = tenant();
        ask(tenant.operator(), tenant.conversation(), "first question");

        ResponseEntity<String> refused = send(tenant.operator(), tenant.conversation(), "second question");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 86_400L);
        assertThat(refused.getBody()).contains("daily LLM budget");
    }

    /** A running execution stops before the turn that would go further past the budget. */
    @Test
    void theDailyBudget_stopsARunningExecution_betweenTurns() {
        Tenant tenant = tenant();

        JsonNode execution = ask(tenant.operator(), tenant.conversation(), "[LOOP] keep checking");

        assertThat(execution.get("status").asString()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(execution.get("statusReason").asString()).isEqualTo("DAILY_BUDGET");
        assertThat(execution.get("budget").get("llmIterations").asInt()).isEqualTo(2);
    }

    /** TM-B3-04: an outage ends the execution cleanly, after the adapter's limited retries. */
    @Test
    void aProviderOutage_failsTheExecution_withAClearReason() {
        Tenant tenant = tenant();
        String marker = uniqueName("outage");

        JsonNode execution = ask(tenant.operator(), tenant.conversation(), "[FAIL] " + marker);

        assertThat(execution.get("status").asString()).isEqualTo("FAILED");
        assertThat(execution.get("statusReason").asString()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(STUB.requests().stream().filter(request -> request.body().contains(marker))).hasSize(3);
    }

    /** Seen on the first real run: an account without credit answered 429 and was retried as a rate limit. */
    @Test
    void anAccountWithoutCredit_failsAtOnce_withItsOwnReason() {
        Tenant tenant = tenant();
        String marker = uniqueName("no-credit");

        JsonNode execution = ask(tenant.operator(), tenant.conversation(), "[NO-CREDIT] " + marker);

        assertThat(execution.get("status").asString()).isEqualTo("FAILED");
        assertThat(execution.get("statusReason").asString()).isEqualTo("LLM_QUOTA_EXHAUSTED");
        assertThat(STUB.requests().stream().filter(request -> request.body().contains(marker))).hasSize(1);
    }
}
