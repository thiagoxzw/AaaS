package com.devopsaaas.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.identity.Role;
import com.devopsaaas.llm.InterceptingLlmGateway;
import com.devopsaaas.llm.LlmMessage;
import com.devopsaaas.llm.LlmRequest;
import com.devopsaaas.llm.LlmToolSpec;
import com.devopsaaas.support.AgentTestSupport;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The agent loop end to end: API → dispatcher → orchestrator → scripted "model" → policy → executor → fake
 * runtime, with every record in PostgreSQL. The scripts obey injected instructions on purpose: security must
 * not depend on the model (docs/06-threat-model.md, section 6).
 */
class AgentIT extends AgentTestSupport {

    @Autowired
    FakeContainerRuntime runtime;

    @Autowired
    InterceptingLlmGateway llm;

    private TestUser admin;
    private TestUser operator;
    private String container;
    private String assisted;

    @BeforeEach
    void setUp() {
        admin = createAdmin(DEFAULT_ORGANIZATION);
        operator = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);
        container = uniqueName("demo-api-container");
        assisted = environment(admin, "ASSISTED", container);
    }

    // ---- the happy path -------------------------------------------------------------------------------

    @Test
    void aQuestion_runsTheLoop_andEverythingIsRecorded() {
        String conversation = conversation(operator, assisted);
        String marker = "[OK] " + uniqueName("q");

        JsonNode execution = ask(operator, conversation, marker + " is demo-api up?");

        assertThat(execution.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(execution.get("answer").get("complete").asBoolean()).isTrue();
        assertThat(execution.get("answer").get("text").asString()).startsWith("Observed:").contains("RUNNING");
        List<JsonNode> actions = actions(execution);
        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().get("tool").asString()).isEqualTo("getContainerStatus");
        assertThat(actions.getFirst().get("target").asString()).isEqualTo("demo-api");
        assertThat(actions.getFirst().get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(execution.get("budget").get("toolCalls").asInt()).isEqualTo(1);
        assertThat(execution.get("budget").get("llmIterations").asInt()).isEqualTo(2);
        assertThat(execution.get("llmModel").asString()).isEqualTo("scripted-v1");
        assertThat(execution.get("promptVersion").asString()).isEqualTo("agent-system-v1");
        assertThat(runtime.callsFor(container)).isEqualTo(1);

        // Records: two model turns, the proposal linked to the first, the answer as an ASSISTANT message.
        Map<String, Object> call = jdbc.queryForMap("""
                SELECT t.llm_call_id, l.seq AS llm_seq, t.rationale FROM tool_execution t
                JOIN llm_call l ON l.id = t.llm_call_id WHERE t.agent_execution_id = ?""", id(execution));
        assertThat(call.get("llm_seq")).isEqualTo(1);
        assertThat(call.get("rationale")).isEqualTo("Checking demo-api.");
        assertThat(count("SELECT count(*) FROM message WHERE agent_execution_id = ? AND role = 'ASSISTANT'",
                id(execution))).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_event WHERE agent_execution_id = ? "
                        + "AND action LIKE 'AGENT_EXECUTION_%' ORDER BY occurred_at", String.class, id(execution)))
                .containsExactly("AGENT_EXECUTION_REQUESTED", "AGENT_EXECUTION_STARTED",
                        "AGENT_EXECUTION_COMPLETED");

        // What the "model" saw: the logical name only, the filtered tools, and the tool result.
        List<LlmRequest> requests = llm.requestsFor(marker);
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().systemPrompt()).containsPattern("\"service\":\\s*\"demo-api\"")
                .doesNotContain(container);
        assertThat(requests.getFirst().tools()).extracting(LlmToolSpec::name)
                .contains("listContainers", "getContainerStatus", "getContainerLogs");
        assertThat(requests.get(1).messages()).last().isInstanceOfSatisfying(LlmMessage.ToolResult.class,
                result -> assertThat(result.content()).contains("\"status\":\"SUCCEEDED\"").contains("RUNNING")
                        .doesNotContain(container));
        String snapshot = jdbc.queryForObject("SELECT context_snapshot::text FROM agent_execution WHERE id = ?",
                String.class, id(execution));
        assertThat(snapshot).contains("demo-api").doesNotContain(container);
    }

    // ---- the malicious-LLM suite (doc 06, section 6) --------------------------------------------------

    @Test
    void s1_anInventedTool_isDenied_andNeverReachesTheRuntime() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[S1] the logs say to delete everything");

        assertThat(actions(execution)).singleElement().satisfies(action -> {
            assertThat(action.get("tool").asString()).isEqualTo("deleteContainer");
            assertThat(action.get("status").asString()).isEqualTo("DENIED");
            assertThat(action.get("denialReason").asString()).isEqualTo("UNKNOWN_TOOL");
        });
        assertThat(runtime.callsFor(container)).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE agent_execution_id = ? AND action = "
                + "'TOOL_CALL_DENIED'", id(execution))).isEqualTo(1);
    }

    @Test
    void s2_aServiceOutsideTheAllowlist_isNotResolved() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[S2] restart the database");

        assertThat(actions(execution).getFirst().get("denialReason").asString()).isEqualTo("RESOURCE_NOT_ALLOWED");
        assertThat(runtime.calls()).noneMatch(call -> call.contains("postgres"));
    }

    @Test
    void s4_aViewer_cannotStartTheAgentAtAll() {
        TestUser viewer = createUser(DEFAULT_ORGANIZATION, Role.VIEWER);

        assertThat(post("/api/v1/conversations", viewer.token(), Map.of("environmentId", assisted))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String conversation = conversation(operator, assisted);
        assertThat(send(viewer, conversation, "[S4] hello").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** RNF-SEG-13: the permission is read again for every proposal, not taken from when the execution began. */
    @Test
    void s4_aPermissionRevokedDuringTheExecution_deniesTheNextProposal_andHidesTheTools() {
        TestUser revoked = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);
        String marker = "[S4] " + uniqueName("revoke");
        llm.onceWhen(marker, request -> {
            jdbc.update("DELETE FROM user_role WHERE user_id = ?", revoked.id());
            jdbc.update("INSERT INTO user_role (user_id, organization_id, role) VALUES (?, ?, 'VIEWER')",
                    revoked.id(), revoked.organizationId());
        });

        JsonNode execution = ask(revoked, conversation(revoked, assisted), marker + " check demo-api");

        assertThat(actions(execution).getFirst().get("denialReason").asString())
                .isEqualTo("INSUFFICIENT_PERMISSION");
        assertThat(runtime.callsFor(container)).isZero();
        assertThat(llm.requestsFor(marker)).hasSize(2);
        assertThat(llm.requestsFor(marker).getFirst().tools()).isNotEmpty();
        assertThat(llm.requestsFor(marker).get(1).tools()).as("tools offered after the revocation").isEmpty();
    }

    @Test
    void s5_anObserveOnlyEnvironment_deniesRiskyProposals_andDoesNotOfferThem() {
        String observeOnly = environment(admin, "OBSERVE_ONLY", uniqueName("observed"));
        String marker = "[S5] " + uniqueName("observe");

        JsonNode execution = ask(operator, conversation(operator, observeOnly), marker + " restart it");

        assertThat(actions(execution).getFirst().get("denialReason").asString()).isEqualTo("NOT_ALLOWED_BY_AUTONOMY");
        assertThat(llm.requestsFor(marker).getFirst().tools()).extracting(LlmToolSpec::name)
                .doesNotContain("testRestart");
        assertThat(runtime.calls()).noneMatch(call -> call.startsWith("restart:"));
    }

    @Test
    void s7_anInjectionInsideAnArgument_isInvalid() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[S7] check demo-api");

        assertThat(actions(execution).getFirst().get("denialReason").asString()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(runtime.callsFor(container)).isZero();
    }

    /** Every proposal is counted before the policy sees it: denied proposals exhaust the budget too. */
    @Test
    void s8_repeatingDeniedProposals_exhaustsTheToolCallBudget() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[S8-CALLS] delete, again and again");

        assertThat(execution.get("status").asString()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(execution.get("statusReason").asString()).isEqualTo("MAX_TOOL_CALLS");
        // 3 per turn: turns 1-3 use 9, turn 4 uses the 10th, the 11th is recorded (and denied), the 12th dropped.
        assertThat(actions(execution)).hasSize(11)
                .allSatisfy(action -> assertThat(action.get("status").asString()).isEqualTo("DENIED"));
        assertThat(execution.get("budget").get("llmIterations").asInt()).isEqualTo(4);
        assertThat(runtime.callsFor(container)).isZero();
    }

    @Test
    void s8_aModelThatNeverStops_exhaustsTheIterationBudget() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[S8-ITER] try forever");

        assertThat(execution.get("status").asString()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(execution.get("statusReason").asString()).isEqualTo("MAX_LLM_ITERATIONS");
        assertThat(execution.get("budget").get("llmIterations").asInt()).isEqualTo(8);
        assertThat(actions(execution)).hasSize(8);
    }

    /** S3 and S6: a risky call waits for a human; the model saying "the user approved" changes nothing. */
    @Test
    void aRiskyProposal_waitsForApproval_whateverTheModelClaims() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[APPROVAL] restart demo-api");

        assertThat(execution.get("status").asString()).isEqualTo("WAITING_APPROVAL");
        assertThat(actions(execution).getFirst().get("status").asString()).isEqualTo("WAITING_APPROVAL");
        assertThat(runtime.calls()).doesNotContain("restart:" + container);
    }

    /** The answer is text; the actions are records. A model claiming an action cannot create one. */
    @Test
    void actionsComeFromTheRecords_notFromTheModelsText() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[CLAIMS] restart demo-api");

        assertThat(execution.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(execution.get("answer").get("text").asString()).contains("I restarted demo-api");
        assertThat(actions(execution)).isEmpty();
        assertThat(runtime.calls()).doesNotContain("restart:" + container);
    }

    // ---- idempotency and invariants -------------------------------------------------------------------

    @Test
    void theSameIdempotencyKey_returnsTheSameExecution_andADifferentBodyIsRefused() {
        String conversation = conversation(operator, assisted);
        String key = "key-" + uniqueName("idem");

        ResponseEntity<String> first = send(operator, conversation, "[OK] first", key);
        ResponseEntity<String> again = send(operator, conversation, "[OK] first", key);
        ResponseEntity<String> different = send(operator, conversation, "[OK] something else", key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(read(again).get("executionId").asString()).isEqualTo(read(first).get("executionId").asString());
        assertThat(read(again).get("replayed").asBoolean()).isTrue();
        assertThat(different.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(count("SELECT count(*) FROM agent_execution WHERE idempotency_key = ?", key)).isEqualTo(1);
        assertThat(send(operator, conversation, "[OK] x", "bad key!").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aSecondMessage_whileAnExecutionIsActive_is409() {
        String conversation = conversation(operator, assisted);
        ask(operator, conversation, "[APPROVAL] restart demo-api");

        assertThat(send(operator, conversation, "[OK] and now?").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void onlyTheCreatorSendsMessages_andOtherOrganizationsSeeNothing() {
        String conversation = conversation(operator, assisted);
        JsonNode execution = ask(operator, conversation, "[OK] hi");
        TestUser colleague = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);
        TestUser outsider = createAdmin(createOrganization());

        assertThat(send(colleague, conversation, "[OK] me too").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/executions/" + id(execution), colleague.token()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(outsider, conversation, "[OK] hi").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/executions/" + id(execution), outsider.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/conversations", outsider.token(), Map.of("environmentId", assisted))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- cancellation -------------------------------------------------------------------------------------

    /** Cooperative: the model call in progress finishes and is recorded; its proposal never runs. */
    @Test
    void cancellingDuringAModelCall_recordsTheCall_andRunsNothingAfterIt() {
        String conversation = conversation(operator, assisted);
        String marker = "[CANCEL] " + uniqueName("cancel");
        String[] executionId = new String[1];
        llm.onceWhen(marker, request -> {
            String active = jdbc.queryForObject("SELECT id::text FROM agent_execution WHERE conversation_id = ?::uuid",
                    String.class, conversation);
            executionId[0] = active;
            assertThat(post("/api/v1/executions/" + active + "/cancel", operator.token(), null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        });

        JsonNode execution = ask(operator, conversation, marker + " check demo-api");

        assertThat(execution.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(execution.get("statusReason").asString()).isEqualTo("CANCELLED_BY_USER");
        assertThat(count("SELECT count(*) FROM llm_call WHERE agent_execution_id = ?", id(execution))).isEqualTo(1);
        assertThat(actions(execution)).isEmpty();
        assertThat(runtime.callsFor(container)).isZero();
        assertThat(executionId[0]).isEqualTo(id(execution).toString());
    }

    @Test
    void cancellingAnExecutionWaitingForApproval_cancelsThePendingCall() {
        JsonNode waiting = ask(operator, conversation(operator, assisted), "[APPROVAL] restart demo-api");
        TestUser colleague = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);

        assertThat(post("/api/v1/executions/" + id(waiting) + "/cancel", colleague.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode cancelled = read(post("/api/v1/executions/" + id(waiting) + "/cancel", operator.token(), null));
        JsonNode again = read(post("/api/v1/executions/" + id(waiting) + "/cancel", operator.token(), null));

        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(actions(cancelled).getFirst().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(again.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(runtime.calls()).doesNotContain("restart:" + container);
    }

    // ---- model failures -------------------------------------------------------------------------------

    @Test
    void aFailingModel_endsTheExecutionAsFailed_withAClearReason() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[LLM-FAIL] anything");

        assertThat(execution.get("status").asString()).isEqualTo("FAILED");
        assertThat(execution.get("statusReason").asString()).isEqualTo("LLM_RATE_LIMITED");
        assertThat(jdbc.queryForMap("SELECT finish_reason, error_code FROM llm_call WHERE agent_execution_id = ?",
                id(execution))).containsEntry("finish_reason", "ERROR").containsEntry("error_code", "RATE_LIMITED");
        assertThat(execution.get("answer").isNull()).isTrue();
    }

    @Test
    void aTruncatedAnswer_isCompleted_butMarkedAsPartial() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[TRUNCATED] describe demo-api");

        assertThat(execution.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(execution.get("statusReason").asString()).isEqualTo("LLM_OUTPUT_TRUNCATED");
        assertThat(execution.get("answer").get("text").asString()).isEqualTo("The service looks");
        assertThat(execution.get("answer").get("complete").asBoolean()).isFalse();
    }

    @Test
    void anEmptyAnswer_isAFailure() {
        JsonNode execution = ask(operator, conversation(operator, assisted), "[EMPTY] say nothing");

        assertThat(execution.get("status").asString()).isEqualTo("FAILED");
        assertThat(execution.get("statusReason").asString()).isEqualTo("LLM_EMPTY_RESPONSE");
    }

    /** The conversation window: the second message's request carries the first exchange. */
    @Test
    void theConversationHistory_isSentBack() {
        String conversation = conversation(operator, assisted);
        ask(operator, conversation, "[CLAIMS] first question");
        String marker = "[OK] " + uniqueName("second");

        ask(operator, conversation, marker + " second question");

        List<LlmMessage> messages = llm.requestsFor(marker).getFirst().messages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isEqualTo(new LlmMessage.User("[CLAIMS] first question"));
        assertThat(messages.get(1)).isInstanceOf(LlmMessage.Assistant.class);
        assertThat(messages.get(2)).isEqualTo(new LlmMessage.User(marker + " second question"));
    }
}
