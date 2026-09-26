package com.devopsaaas.tool.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.devopsaaas.identity.Role;
import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.support.IntegrationTest;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import com.devopsaaas.tool.policy.DenialReason;
import com.devopsaaas.tool.policy.PolicyContext;
import com.devopsaaas.tool.policy.ToolProposal;
import com.devopsaaas.tool.testing.TestTools;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole chain against the real application and PostgreSQL, with test tools and a fake runtime:
 * docs/03-arquitetura.md 5.2, docs/05-contratos-das-ferramentas.md 6, RF-44/49, RNF-SEG-07b/11, RNF-CONF-06/07.
 */
class ToolExecutorIT extends IntegrationTest {

    @Autowired
    ToolExecutor executor;

    @Autowired
    FakeContainerRuntime runtime;

    private TestUser admin;
    private TestUser operator;
    private UUID assisted;
    private UUID demoApiServiceId;
    private String demoApiContainer;

    @BeforeEach
    void environment() {
        admin = createAdmin(DEFAULT_ORGANIZATION);
        operator = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);
        assisted = UUID.fromString(createEnvironment(admin, uniqueName("assisted")).get("id").asString());
        demoApiContainer = uniqueName("demo-api-container");
        demoApiServiceId = UUID.fromString(
                allowlistService(admin, assisted.toString(), "demo-api", demoApiContainer).get("id").asString());
    }

    // ---- allowed calls -----------------------------------------------------------------------------------

    @Test
    void allowedReadOnlyCall_runsThroughThePort_andIsPersistedAndAuditedAsAgentOnBehalfOfTheUser() {
        ToolExecutionOutcome outcome = run(operator, assisted, "testStatus", "{\"service\":\"demo-api\"}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(outcome.output()).contains("\"serviceName\":\"demo-api\"").doesNotContain(demoApiContainer);
        assertThat(runtime.callsFor(demoApiContainer)).isEqualTo(1);

        Map<String, Object> row = row(outcome.toolExecutionId());
        assertThat(row).containsEntry("status", "SUCCEEDED").containsEntry("attempt_count", 1)
                .containsEntry("policy_decision", "ALLOW").containsEntry("target_service_id", demoApiServiceId);
        assertThat(row.get("arguments_hash")).asString().hasSize(64);

        List<Map<String, Object>> audit = audit(outcome.toolExecutionId());
        assertThat(audit).extracting(event -> event.get("action"))
                .containsExactly("TOOL_EXECUTION_STARTED", "TOOL_EXECUTION_SUCCEEDED");
        assertThat(audit).allSatisfy(event -> {
            assertThat(event).containsEntry("actor_type", "AGENT").containsEntry("on_behalf_of_user_id", operator.id())
                    .containsEntry("resource_type", "SERVICE").containsEntry("resource_id", demoApiServiceId)
                    .containsEntry("tool_name", "testStatus");
            assertThat(event.get("actor_user_id")).isNull();
        });
    }

    @Test
    void executionIsCommittedAsRunning_beforeTheToolIsCalled() {
        ToolExecutionOutcome outcome = run(operator, assisted, "testProbe", "{}");

        assertThat(outcome.output()).contains("\"statusSeenDuringCall\":\"RUNNING\"");
    }

    // ---- the validation chain ----------------------------------------------------------------------------

    @Test
    void unknownTool_isDenied_persisted_andAudited_withoutTouchingTheRuntime() {
        ToolExecutionOutcome outcome = run(operator, assisted, "deleteContainer", "{\"service\":\"demo-api\"}");

        assertDenied(outcome, DenialReason.UNKNOWN_TOOL);
        assertThat(row(outcome.toolExecutionId())).containsEntry("tool_name", "deleteContainer");
        assertThat(audit(outcome.toolExecutionId())).extracting(event -> event.get("action"))
                .containsExactly("TOOL_CALL_DENIED");
        assertThat(runtime.callsFor(demoApiContainer)).isZero();
    }

    @Test
    void invalidArguments_areDenied() {
        assertDenied(run(operator, assisted, "testStatus", "{\"service\":\"demo-api\",\"force\":true}"),
                DenialReason.INVALID_ARGUMENTS);
        assertDenied(run(operator, assisted, "testStatus", "{\"service\":\"demo-api\\\"; rm -rf /\"}"),
                DenialReason.INVALID_ARGUMENTS);
        assertDenied(run(operator, assisted, "testStatus", "not json"), DenialReason.INVALID_ARGUMENTS);
        assertDenied(run(operator, assisted, "testFlaky", "{\"key\":\"k\",\"failures\":\"2\"}"),
                DenialReason.INVALID_ARGUMENTS);
        assertThat(runtime.callsFor(demoApiContainer)).isZero();
    }

    @Test
    void servicesOutsideTheAllowlist_orDisabled_areNotResolvable() {
        JsonServices disabled = new JsonServices(allowlistService(admin, assisted.toString(), "legacy", uniqueName("c")));
        patch("/api/v1/environments/" + assisted + "/services/" + disabled.id(), admin.token(),
                Map.of("enabled", false, "version", disabled.version()));

        assertDenied(run(operator, assisted, "testStatus", "{\"service\":\"postgres\"}"),
                DenialReason.RESOURCE_NOT_ALLOWED);
        assertDenied(run(operator, assisted, "testStatus", "{\"service\":\"legacy\"}"),
                DenialReason.RESOURCE_NOT_ALLOWED);
    }

    @Test
    void anotherOrganizationsEnvironment_isUnavailable() {
        TestUser adminOfB = createAdmin(createOrganization());
        UUID environmentOfB = UUID.fromString(createEnvironment(adminOfB, uniqueName("b")).get("id").asString());

        assertDenied(run(operator, environmentOfB, "testStatus", "{\"service\":\"demo-api\"}"),
                DenialReason.ENVIRONMENT_UNAVAILABLE);
    }

    @Test
    void theAgentNeverHasMorePowerThanTheRequester() {
        TestUser viewer = createUser(DEFAULT_ORGANIZATION, Role.VIEWER);

        assertDenied(run(viewer, assisted, "testStatus", "{\"service\":\"demo-api\"}"),
                DenialReason.INSUFFICIENT_PERMISSION);
    }

    @Test
    void revokedPermission_isEnforcedOnTheNextProposal() {
        assertThat(run(operator, assisted, "testStatus", "{\"service\":\"demo-api\"}").status())
                .isEqualTo(ToolExecutionStatus.SUCCEEDED);

        jdbc.update("DELETE FROM user_role WHERE user_id = ?", operator.id());

        assertDenied(run(operator, assisted, "testStatus", "{\"service\":\"demo-api\"}"),
                DenialReason.INSUFFICIENT_PERMISSION);
    }

    @Test
    void observeOnly_deniesAnythingButReadOnly() {
        UUID observeOnly = UUID.fromString(createEnvironment(admin, uniqueName("observe"), "OBSERVE_ONLY")
                .get("id").asString());
        String container = uniqueName("observed");
        allowlistService(admin, observeOnly.toString(), "demo-api", container);

        assertDenied(run(operator, observeOnly, "testRestart", "{\"service\":\"demo-api\"}"),
                DenialReason.NOT_ALLOWED_BY_AUTONOMY);
        assertThat(run(operator, observeOnly, "testStatus", "{\"service\":\"demo-api\"}").status())
                .isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(runtime.calls()).doesNotContain("restart:" + container);
    }

    @Test
    void exhaustedBudget_isDenied() {
        ToolExecutionOutcome outcome = executor.execute(new ToolExecutionRequest(
                new PolicyContext(DEFAULT_ORGANIZATION, assisted, operator.id(), 0), Ids.newId(), null, 1,
                new ToolProposal("testStatus", "{\"service\":\"demo-api\"}", "call", null)));

        assertDenied(outcome, DenialReason.BUDGET_EXCEEDED);
    }

    @Test
    void highRiskCall_waitsForApproval_andIsNotExecuted() {
        ToolExecutionOutcome outcome = run(operator, assisted, "testRestart", "{\"service\":\"demo-api\"}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.WAITING_APPROVAL);
        assertThat(row(outcome.toolExecutionId())).containsEntry("policy_decision", "REQUIRE_APPROVAL");
        assertThat(audit(outcome.toolExecutionId())).extracting(event -> event.get("action"))
                .containsExactly("TOOL_CALL_AWAITING_APPROVAL");
        assertThat(runtime.calls()).doesNotContain("restart:" + demoApiContainer);
    }

    // ---- retries, timeouts, failures ----------------------------------------------------------------------

    @Test
    void retryableReadOnlyTool_isRetriedOnTransientFailures() {
        ToolExecutionOutcome outcome =
                run(operator, assisted, "testFlaky", "{\"key\":\"" + uniqueName("k") + "\",\"failures\":2}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(outcome.attempts()).isEqualTo(3);
    }

    @Test
    void retries_stopAfterThreeExtraAttempts() {
        ToolExecutionOutcome outcome = run(operator, assisted, "testUnavailable", "{}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.RUNTIME_UNAVAILABLE);
        assertThat(outcome.attempts()).isEqualTo(4);
    }

    @Test
    void readOnlyTimeout_endsAsTimedOut() {
        ToolExecutionOutcome outcome = run(operator, assisted, "testSlowRead", "{}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.TIMED_OUT);
    }

    @Test
    void sideEffectTimeout_endsAsOutcomeUnknown_andIsNeverRetried() {
        ToolExecutionOutcome outcome = run(operator, assisted, "testSlowWrite", "{}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(audit(outcome.toolExecutionId())).extracting(event -> event.get("action"))
                .containsExactly("TOOL_EXECUTION_STARTED", "TOOL_EXECUTION_OUTCOME_UNKNOWN");
    }

    @Test
    void unexpectedException_endsAsInternalError_withoutLeakingItsMessage() {
        ToolExecutionOutcome outcome = run(operator, assisted, "testCrashing", "{}");

        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.INTERNAL_ERROR);
        assertThat(String.valueOf(row(outcome.toolExecutionId()).get("error_message")))
                .doesNotContain("internal detail").doesNotContain("IllegalStateException");
    }

    // ---- output cleaning ------------------------------------------------------------------------------------

    @Test
    void secrets_neverReachTheDatabaseInPlainText() {
        ToolExecutionOutcome outcome = run(operator, assisted, "testLeaky", "{}");

        String stored = jdbc.queryForObject("SELECT output::text FROM tool_execution WHERE id = ?", String.class,
                outcome.toolExecutionId());
        assertThat(stored)
                .doesNotContain(TestTools.LeakyTool.SECRET)
                .doesNotContain("abcdefghijklmnop123456")
                .doesNotContain("\\u001b")
                .contains("<redacted>")
                .contains("<U+202E>");
        assertThat(outcome.output()).doesNotContain(TestTools.LeakyTool.SECRET);
        assertThat((Integer) row(outcome.toolExecutionId()).get("redaction_count")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void oversizedOutput_isReplacedByAValidPreview() {
        ToolExecutionOutcome outcome = run(operator, assisted, "testBigOutput", "{}");

        assertThat(outcome.outputTruncated()).isTrue();
        assertThat(read(outcome.output()).get("truncated").asBoolean()).isTrue();
        assertThat(outcome.output().length()).isLessThan(1100);
    }

    @Test
    void argumentsWithSecrets_areStoredRedacted() {
        ToolExecutionOutcome outcome =
                run(operator, assisted, "deleteContainer", "{\"token\":\"password=letmein-please\"}");

        String stored = jdbc.queryForObject("SELECT arguments::text FROM tool_execution WHERE id = ?", String.class,
                outcome.toolExecutionId());
        assertThat(stored).doesNotContain("letmein-please").contains("<redacted>");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private record JsonServices(String id, long version) {
        JsonServices(tools.jackson.databind.JsonNode node) {
            this(node.get("id").asString(), node.get("version").asLong());
        }
    }

    private ToolExecutionOutcome run(TestUser user, UUID environmentId, String tool, String argumentsJson) {
        return executor.execute(new ToolExecutionRequest(
                new PolicyContext(user.organizationId(), environmentId, user.id(), 10),
                Ids.newId(), null, 1, new ToolProposal(tool, argumentsJson, "call-1", "because the test says so")));
    }

    private static void assertDenied(ToolExecutionOutcome outcome, DenialReason reason) {
        assertThat(outcome.status()).isEqualTo(ToolExecutionStatus.DENIED);
        assertThat(outcome.denialReason()).isEqualTo(reason);
    }

    private Map<String, Object> row(UUID toolExecutionId) {
        return jdbc.queryForMap("SELECT * FROM tool_execution WHERE id = ?", toolExecutionId);
    }

    private List<Map<String, Object>> audit(UUID toolExecutionId) {
        return jdbc.queryForList("SELECT * FROM audit_event WHERE tool_execution_id = ? ORDER BY occurred_at, id",
                toolExecutionId);
    }

    private tools.jackson.databind.JsonNode read(String json) {
        return this.json.readTree(json);
    }
}
