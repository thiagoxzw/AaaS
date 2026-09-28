package com.devopsaaas.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.devopsaaas.agent.AgentStartupRecovery;
import com.devopsaaas.agent.ApprovalResumption;
import com.devopsaaas.identity.Role;
import com.devopsaaas.llm.InterceptingLlmGateway;
import com.devopsaaas.llm.LlmMessage;
import com.devopsaaas.support.AgentTestSupport;
import com.devopsaaas.tool.container.FakeContainerRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Slice 7 end to end, through the HTTP API as a client would use it: the scripted "model" proposes the
 * test-only HIGH_RISK tool {@code testRestart}, the execution pauses, a human decides, and the execution
 * resumes. The fake runtime counts restarts, so "ran once", "never ran" and "ran twice" are all observable.
 */
class ApprovalIT extends AgentTestSupport {

    private static final List<String> NOT_FINISHED = List.of("QUEUED", "RUNNING", "WAITING_APPROVAL");

    @Autowired
    FakeContainerRuntime runtime;

    @Autowired
    InterceptingLlmGateway llm;

    @Autowired
    ApprovalResumption resumption;

    @Autowired
    AgentStartupRecovery recovery;

    @Autowired
    MeterRegistry meters;

    private TestUser admin;
    private TestUser operator;
    private TestUser approver;
    private String container;
    private String environment;

    @BeforeEach
    void setUp() {
        admin = createAdmin(DEFAULT_ORGANIZATION);
        operator = createUser(DEFAULT_ORGANIZATION, Role.OPERATOR);
        approver = createUser(DEFAULT_ORGANIZATION, Role.APPROVER);
        container = uniqueName("approval-container");
        environment = environment(admin, "ASSISTED", container);
    }

    // ---- the whole flow ----------------------------------------------------------------------------------

    /** The demonstration of the slice: pause, the approval as the contract shows it, approve, resume. */
    @Test
    void anApprovedCall_runsExactlyAsRecorded_andTheExecutionResumes() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-EVIDENCE] demo-api is slow");
        assertThat(waiting.get("status").asString()).isEqualTo("WAITING_APPROVAL");
        assertThat(runtime.calls()).doesNotContain("restart:" + container);
        JsonNode summary = waiting.get("approvals").get(0);
        assertThat(summary.get("status").asString()).isEqualTo("PENDING");
        String approvalId = summary.get("approvalId").asString();

        assertThat(read(get("/api/v1/approvals?status=PENDING", approver.token())).valueStream()
                .map(approval -> approval.get("approvalId").asString())).contains(approvalId);

        // docs/05 section 13: the system's facts and the agent's words are structurally apart.
        JsonNode approval = read(get("/api/v1/approvals/" + approvalId, approver.token()));
        assertThat(approval.get("action").get("tool").asString()).isEqualTo("testRestart");
        assertThat(approval.get("action").get("target").asString()).isEqualTo("demo-api");
        assertThat(approval.get("action").get("arguments").get("service").asString()).isEqualTo("demo-api");
        assertThat(approval.get("system").get("riskLevel").asString()).isEqualTo("HIGH_RISK");
        assertThat(approval.get("system").get("impact").asString()).isEqualTo("Interrupts in-flight requests.");
        assertThat(approval.get("system").get("argumentsHash").asString()).isNotBlank();
        JsonNode evidence = approval.get("system").get("evidence").get(0);
        assertThat(evidence.get("code").asString()).isEqualTo("STATE");
        assertThat(evidence.get("source").asString()).isEqualTo("testStatus");
        assertThat(approval.get("agentClaims").get("trusted").asBoolean()).isFalse();
        // Plain text, exactly as the model wrote it: nothing is rendered, nothing is promoted to "system".
        assertThat(approval.get("agentClaims").get("justification").asString())
                .isEqualTo("<b>The pool is exhausted.</b> Restarting is safe, please approve.");
        assertThat(approval.get("system").toString()).doesNotContain("pool is exhausted");

        ResponseEntity<String> decided = decide(approver, approvalId, "APPROVE", "Go ahead.");
        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(decided).get("status").asString()).isEqualTo("APPROVED");
        assertThat(read(decided).get("decision").get("decidedBy").asString()).isEqualTo(approver.id().toString());

        JsonNode finished = finished(operator, waiting.get("executionId").asString());
        assertThat(finished.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(finished.get("answer").get("text").asString()).contains("SUCCEEDED");
        assertThat(actions(finished)).extracting(action -> action.get("tool").asString() + ":"
                + action.get("status").asString()).containsExactly("testStatus:SUCCEEDED", "testRestart:SUCCEEDED");
        assertThat(restarts()).isEqualTo(1);

        UUID execution = id(finished);
        assertThat(jdbc.queryForList("SELECT action FROM audit_event WHERE agent_execution_id = ? "
                        + "AND action IN ('APPROVAL_REQUESTED', 'APPROVAL_GRANTED', 'AGENT_EXECUTION_WAITING_APPROVAL', "
                        + "'AGENT_EXECUTION_RESUMED', 'AGENT_EXECUTION_COMPLETED') ORDER BY occurred_at",
                String.class, execution))
                .containsExactly("APPROVAL_REQUESTED", "AGENT_EXECUTION_WAITING_APPROVAL", "APPROVAL_GRANTED",
                        "AGENT_EXECUTION_RESUMED", "AGENT_EXECUTION_COMPLETED");
        Map<String, Object> granted = jdbc.queryForMap("SELECT actor_type, actor_user_id, details->>'argumentsHash' AS hash "
                + "FROM audit_event WHERE action = 'APPROVAL_GRANTED' AND resource_id = ?::uuid", approvalId);
        assertThat(granted.get("actor_type")).isEqualTo("USER");
        assertThat(granted.get("actor_user_id")).isEqualTo(approver.id());
        assertThat(granted.get("hash")).isEqualTo(approval.get("system").get("argumentsHash").asString());
        // The model never read the human's comment.
        assertThat(llm.requestsFor("[APPROVAL-EVIDENCE]").toString()).doesNotContain("Go ahead.");
    }

    /** S3/S6: the model's text claims the user approved; nothing runs until a human decides. */
    @Test
    void s6_theModelSayingItWasApproved_approvesNothing() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL] restart demo-api");

        resumption.sweep();

        assertThat(read(get("/api/v1/executions/" + waiting.get("executionId").asString(), operator.token()))
                .get("status").asString()).isEqualTo("WAITING_APPROVAL");
        assertThat(approvalOf(waiting).get("status").asString()).isEqualTo("PENDING");
        assertThat(restarts()).isZero();
    }

    @Test
    void aRejectedCall_neverRuns_andTheModelOnlyLearnsItWasRejected() {
        String question = echo();
        JsonNode waiting = ask(operator, conversation(operator, environment), question);
        String approvalId = approvalOf(waiting).get("approvalId").asString();

        assertThat(decide(approver, approvalId, "REJECT", "Not during business hours.").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode finished = finished(operator, waiting.get("executionId").asString());
        assertThat(finished.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(actions(finished).getFirst().get("status").asString()).isEqualTo("REJECTED");
        assertThat(restarts()).isZero();
        List<String> results = toolResults(question);
        assertThat(results).containsExactly("{\"status\":\"REJECTED\"}");
        assertThat(llm.requestsFor(question).toString()).doesNotContain("business hours");
    }

    // ---- replay, concurrency, TOCTOU ---------------------------------------------------------------------

    /** TM-B7-03: an approval is used once. */
    @Test
    void anApproval_cannotBeDecidedTwice() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-ECHO] restart demo-api");
        String approvalId = approvalOf(waiting).get("approvalId").asString();

        assertThat(decide(approver, approvalId, "APPROVE", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> again = decide(approver, approvalId, "APPROVE", null);
        ResponseEntity<String> flipped = decide(admin, approvalId, "REJECT", null);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(flipped.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(finished(operator, waiting.get("executionId").asString()).get("status").asString())
                .isEqualTo("COMPLETED");
        resumption.sweep();
        assertThat(restarts()).isEqualTo(1);
    }

    /** S10: the same approval sent in parallel, and the execution resumed in parallel: exactly one restart. */
    @Test
    void s10_concurrentDecisionsAndResumptions_restartExactlyOnce() throws Exception {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-ECHO] restart demo-api");
        String approvalId = approvalOf(waiting).get("approvalId").asString();

        List<Integer> statuses = inParallel(8, () -> decide(approver, approvalId, "APPROVE", null)
                .getStatusCode().value());
        inParallel(4, () -> resumption.sweep().resumed());

        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(finished(operator, waiting.get("executionId").asString()).get("status").asString())
                .isEqualTo("COMPLETED");
        assertThat(restarts()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'APPROVAL_GRANTED' AND resource_id = ?::uuid",
                approvalId)).isEqualTo(1);
    }

    /** S11 / TM-B7-04: the approval is accepted, but the policy evaluated NOW denies the call. */
    @Test
    void s11_autonomyLoweredWhilePending_theApprovalStands_butTheCallIsDenied() {
        String question = echo();
        JsonNode waiting = ask(operator, conversation(operator, environment), question);
        String approvalId = approvalOf(waiting).get("approvalId").asString();
        JsonNode current = read(get("/api/v1/environments/" + environment, admin.token()));
        assertThat(patch("/api/v1/environments/" + environment, admin.token(), Map.of(
                "autonomyLevel", "OBSERVE_ONLY", "version", current.get("version").asLong()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> decided = decide(approver, approvalId, "APPROVE", null);

        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(decided).get("status").asString()).isEqualTo("APPROVED");
        JsonNode finished = finished(operator, waiting.get("executionId").asString());
        JsonNode action = actions(finished).getFirst();
        assertThat(action.get("status").asString()).isEqualTo("DENIED");
        assertThat(action.get("denialReason").asString()).isEqualTo("NOT_ALLOWED_BY_AUTONOMY");
        assertThat(restarts()).isZero();
        assertThat(toolResults(question)).containsExactly(
                "{\"status\":\"DENIED\",\"reason\":\"NOT_ALLOWED_BY_AUTONOMY\"}");
    }

    /** TM-B7-02: the stored arguments now point elsewhere; the bound hash differs and nothing runs. */
    @Test
    void storedArgumentsChangedAfterTheProposal_areDenied_withArgumentsMismatch() {
        String otherContainer = uniqueName("other-container");
        allowlistService(admin, environment, "other-api", otherContainer);
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-ECHO] restart demo-api");
        UUID toolExecution = UUID.fromString(approvalOf(waiting).get("toolExecutionId").asString());
        jdbc.update("UPDATE tool_execution SET arguments = '{\"service\":\"other-api\"}'::jsonb WHERE id = ?",
                toolExecution);

        decide(approver, approvalOf(waiting).get("approvalId").asString(), "APPROVE", null);

        JsonNode action = actions(finished(operator, waiting.get("executionId").asString())).getFirst();
        assertThat(action.get("status").asString()).isEqualTo("DENIED");
        assertThat(action.get("denialReason").asString()).isEqualTo("ARGUMENTS_MISMATCH");
        assertThat(runtime.callsFor(otherContainer)).isZero();
        assertThat(restarts()).isZero();
    }

    /** TM-B7-02: the call's own hash was changed too; the approval's copy still does not match. */
    @Test
    void aChangedCallHash_isDenied_withArgumentsMismatch() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-ECHO] restart demo-api");
        jdbc.update("UPDATE tool_execution SET arguments_hash = 'forged' WHERE id = ?",
                UUID.fromString(approvalOf(waiting).get("toolExecutionId").asString()));

        decide(approver, approvalOf(waiting).get("approvalId").asString(), "APPROVE", null);

        JsonNode action = actions(finished(operator, waiting.get("executionId").asString())).getFirst();
        assertThat(action.get("denialReason").asString()).isEqualTo("ARGUMENTS_MISMATCH");
        assertThat(restarts()).isZero();
    }

    // ---- expiration --------------------------------------------------------------------------------------

    /** RF-43: a late decision finds the approval expired and closes it; the execution goes on. */
    @Test
    void aLateDecision_findsTheApprovalExpired() {
        String question = echo();
        JsonNode waiting = ask(operator, conversation(operator, environment), question);
        String approvalId = approvalOf(waiting).get("approvalId").asString();
        jdbc.update("UPDATE approval SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", approvalId);

        ResponseEntity<String> late = decide(approver, approvalId, "APPROVE", null);

        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(read(late).get("detail").asString()).contains("expired");
        assertThat(read(get("/api/v1/approvals/" + approvalId, approver.token())).get("status").asString())
                .isEqualTo("EXPIRED");
        JsonNode finished = finished(operator, waiting.get("executionId").asString());
        assertThat(actions(finished).getFirst().get("status").asString()).isEqualTo("EXPIRED");
        assertThat(toolResults(question)).containsExactly("{\"status\":\"EXPIRED\"}");
        assertThat(restarts()).isZero();
    }

    /** The sweep is idempotent and relies on the stored state only. */
    @Test
    void theSweep_expiresWhatIsDue_once_andResumesTheExecution() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-ECHO] restart demo-api");
        String approvalId = approvalOf(waiting).get("approvalId").asString();
        jdbc.update("UPDATE approval SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", approvalId);

        assertThat(resumption.sweep().expired()).isGreaterThanOrEqualTo(1);
        JsonNode finished = finished(operator, waiting.get("executionId").asString());
        assertThat(finished.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(actions(finished).getFirst().get("status").asString()).isEqualTo("EXPIRED");

        resumption.sweep();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'APPROVAL_EXPIRED' AND resource_id = ?::uuid",
                approvalId)).isEqualTo(1);
        assertThat(decide(approver, approvalId, "APPROVE", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(restarts()).isZero();
    }

    // ---- who may decide ----------------------------------------------------------------------------------

    /** TM-B7-01: the permission is checked with the user's state now, not the one in the token. */
    @Test
    void onlyAUserHoldingApprovalDecideNow_canDecide() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-ECHO] restart demo-api");
        String approvalId = approvalOf(waiting).get("approvalId").asString();
        TestUser demoted = createUser(DEFAULT_ORGANIZATION, Role.APPROVER);
        jdbc.update("DELETE FROM user_role WHERE user_id = ?", demoted.id());
        jdbc.update("INSERT INTO user_role (user_id, organization_id, role) VALUES (?, ?, 'VIEWER')",
                demoted.id(), demoted.organizationId());

        assertThat(decide(operator, approvalId, "APPROVE", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(demoted, approvalId, "APPROVE", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        TestUser outsider = createUser(createOrganization(), Role.APPROVER);
        assertThat(decide(outsider, approvalId, "APPROVE", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/approvals/" + approvalId, outsider.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(decide(approver, approvalId, "MAYBE", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(decide(approver, approvalId, "APPROVE", "x".repeat(1001)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(approvalOf(waiting).get("status").asString()).isEqualTo("PENDING");
        assertThat(restarts()).isZero();
    }

    /** TM-B7-05, accepted in the MVP: the requester may approve their own request (four eyes is V5). */
    @Test
    void selfApproval_isAllowedInTheMvp() {
        JsonNode waiting = ask(admin, conversation(admin, environment), "[APPROVAL-ECHO] restart demo-api");

        assertThat(decide(admin, approvalOf(waiting).get("approvalId").asString(), "APPROVE", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finished(admin, waiting.get("executionId").asString()).get("status").asString())
                .isEqualTo("COMPLETED");
        assertThat(restarts()).isEqualTo(1);
    }

    // ---- several approvals, cancellation, atomicity, restart ---------------------------------------------

    @Test
    void twoRiskyCallsInOneTurn_resumeOnlyWhenBothAreDecided() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-TWO] restart demo-api");
        List<JsonNode> approvals = waiting.get("approvals").valueStream().toList();
        assertThat(approvals).hasSize(2);

        decide(approver, approvals.get(0).get("approvalId").asString(), "APPROVE", null);
        resumption.sweep();
        assertThat(read(get("/api/v1/executions/" + waiting.get("executionId").asString(), operator.token()))
                .get("status").asString()).isEqualTo("WAITING_APPROVAL");
        assertThat(restarts()).isZero();

        decide(approver, approvals.get(1).get("approvalId").asString(), "REJECT", null);

        JsonNode finished = finished(operator, waiting.get("executionId").asString());
        assertThat(actions(finished)).extracting(action -> action.get("status").asString())
                .containsExactly("SUCCEEDED", "REJECTED");
        assertThat(restarts()).isEqualTo(1);
    }

    @Test
    void cancellingTheExecution_cancelsItsApprovals() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-ECHO] restart demo-api");
        String approvalId = approvalOf(waiting).get("approvalId").asString();

        assertThat(post("/api/v1/executions/" + waiting.get("executionId").asString() + "/cancel", operator.token(),
                null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(read(get("/api/v1/approvals/" + approvalId, approver.token())).get("status").asString())
                .isEqualTo("CANCELLED");
        assertThat(decide(approver, approvalId, "APPROVE", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        resumption.sweep();
        assertThat(restarts()).isZero();
    }

    /**
     * Slice 9a: a decision and a cancellation at the same time, several rounds so the interleaving varies.
     * Whichever wins, the restart runs at most once, only for a granted approval, and never for a call the
     * cancellation reached first.
     */
    @Test
    void approvingAndCancellingAtTheSameTime_restartsAtMostOnce_andNeverACancelledCall() throws Exception {
        for (int round = 0; round < 6; round++) {
            JsonNode waiting = ask(operator, conversation(operator, environment), echo());
            String executionId = waiting.get("executionId").asString();
            String approvalId = approvalOf(waiting).get("approvalId").asString();
            long before = restarts();

            List<Integer> statuses = race(
                    () -> decide(approver, approvalId, "APPROVE", null).getStatusCode().value(),
                    () -> post("/api/v1/executions/" + executionId + "/cancel", operator.token(), null)
                            .getStatusCode().value());

            JsonNode finished = finished(operator, executionId);
            String approval = jdbc.queryForObject("SELECT status FROM approval WHERE id = ?::uuid", String.class,
                    approvalId);
            String call = actions(finished).getFirst().get("status").asString();
            long ran = restarts() - before;
            assertThat(ran).as("round %d", round).isLessThanOrEqualTo(1);
            assertThat(approval).isIn("APPROVED", "CANCELLED");
            if (ran == 1) {
                assertThat(approval).isEqualTo("APPROVED");
                assertThat(call).isEqualTo("SUCCEEDED");
            } else {
                assertThat(call).isEqualTo("CANCELLED");
            }
            if (approval.equals("CANCELLED")) {
                assertThat(statuses.getFirst()).isEqualTo(409);
            }
            resumption.sweep();
            assertThat(restarts() - before).isEqualTo(ran);
        }
    }

    /**
     * Slice 9a: a decision racing the expiration. The approval ends APPROVED or EXPIRED, with exactly one of
     * the two audit events, and the restart runs if and only if it was approved.
     */
    @Test
    void approvingAtTheMomentItExpires_endsInExactlyOneOutcome() throws Exception {
        for (int round = 0; round < 6; round++) {
            JsonNode waiting = ask(operator, conversation(operator, environment), echo());
            String executionId = waiting.get("executionId").asString();
            String approvalId = approvalOf(waiting).get("approvalId").asString();
            long before = restarts();
            long delay = 60 + 15L * round;
            jdbc.update("UPDATE approval SET expires_at = now() + interval '100 milliseconds' WHERE id = ?::uuid",
                    approvalId);

            List<Integer> statuses = race(
                    () -> {
                        Thread.sleep(delay);
                        return decide(approver, approvalId, "APPROVE", null).getStatusCode().value();
                    },
                    () -> {
                        long end = System.nanoTime() + Duration.ofMillis(400).toNanos();
                        while (System.nanoTime() < end) {
                            resumption.sweep();
                        }
                        return 0;
                    });

            finished(operator, executionId);
            String approval = jdbc.queryForObject("SELECT status FROM approval WHERE id = ?::uuid", String.class,
                    approvalId);
            assertThat(approval).as("round %d", round).isIn("APPROVED", "EXPIRED");
            assertThat(count("SELECT count(*) FROM audit_event WHERE resource_id = ?::uuid "
                    + "AND action IN ('APPROVAL_GRANTED', 'APPROVAL_EXPIRED')", approvalId)).isEqualTo(1);
            assertThat(restarts() - before).isEqualTo(approval.equals("APPROVED") ? 1 : 0);
            assertThat(statuses.getFirst()).isEqualTo(approval.equals("APPROVED") ? 200 : 409);
        }
    }

    /**
     * Slice 9b: devops.approvals counts each committed transition once, by status and tool, and
     * devops.approval.wait times the ones that ended the wait. Cumulative counters, so the test reads deltas.
     */
    @Test
    void approvalMetrics_countEachCommittedTransitionOnce() {
        double requested = approvals("PENDING");
        double granted = approvals("APPROVED");
        double rejected = approvals("REJECTED");
        long grantedWaits = meters.timer("devops.approval.wait", "status", "APPROVED").count();

        JsonNode first = ask(operator, conversation(operator, environment), echo());
        JsonNode second = ask(operator, conversation(operator, environment), echo());
        decide(approver, approvalOf(first).get("approvalId").asString(), "APPROVE", null);
        decide(approver, approvalOf(second).get("approvalId").asString(), "REJECT", null);
        decide(approver, approvalOf(second).get("approvalId").asString(), "APPROVE", null);
        finished(operator, first.get("executionId").asString());
        finished(operator, second.get("executionId").asString());

        assertThat(approvals("PENDING") - requested).isEqualTo(2);
        assertThat(approvals("APPROVED") - granted).as("the late second decision is a 409, not a count").isOne();
        assertThat(approvals("REJECTED") - rejected).isOne();
        assertThat(meters.timer("devops.approval.wait", "status", "APPROVED").count() - grantedWaits).isOne();
    }

    private double approvals(String status) {
        return meters.counter("devops.approvals", "status", status, "tool", "testRestart").count();
    }

    /** TM-B7-07: if the audit event cannot be written, the decision is not written either. */
    @Test
    void theDecisionAndItsAuditEvent_areAtomic() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-ECHO] restart demo-api");
        String approvalId = approvalOf(waiting).get("approvalId").asString();
        double grantedBefore = approvals("APPROVED");
        String trigger = "fail_audit_" + approvalId.replace("-", "");
        jdbc.execute("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.resource_id = '" + approvalId + "' AND NEW.action = 'APPROVAL_GRANTED' THEN "
                + "RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION "
                + trigger + "()");
        try {
            ResponseEntity<String> failed = decide(approver, approvalId, "APPROVE", null);
            assertThat(failed.getStatusCode().is5xxServerError()).isTrue();
            // TM-B1-06 (slice 9a): the database's error, its SQL and the stack trace stay out of the response.
            assertThat(failed.getBody()).doesNotContain("audit unavailable").doesNotContain("PSQL")
                    .doesNotContain("insert into").doesNotContain("Exception").doesNotContain("at com.devopsaaas");
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON audit_event");
            jdbc.execute("DROP FUNCTION " + trigger + "()");
        }

        assertThat(jdbc.queryForObject("SELECT status FROM approval WHERE id = ?::uuid", String.class, approvalId))
                .isEqualTo("PENDING");
        // Slice 9b: the rolled-back decision is not in the metrics either (counted after the commit only).
        assertThat(approvals("APPROVED")).isEqualTo(grantedBefore);
        resumption.sweep();
        assertThat(restarts()).isZero();
    }

    /** RNF-CONF-08: the pause lives in the database; a decision the process never acted on is picked up. */
    @Test
    void aWaitingExecution_survivesARestart_andADecisionLeftBehindIsResumedByTheSweep() {
        JsonNode waiting = ask(operator, conversation(operator, environment), "[APPROVAL-ECHO] restart demo-api");
        String executionId = waiting.get("executionId").asString();
        String approvalId = approvalOf(waiting).get("approvalId").asString();

        recovery.recover();
        assertThat(read(get("/api/v1/executions/" + executionId, operator.token())).get("status").asString())
                .isEqualTo("WAITING_APPROVAL");

        // As if the decision committed and the process died before resuming anything.
        jdbc.update("UPDATE approval SET status = 'APPROVED', decided_by = ?, decided_at = now() WHERE id = ?::uuid",
                approver.id(), approvalId);
        recovery.recover();
        assertThat(restarts()).isZero();

        assertThat(resumption.sweep().resumed()).isGreaterThanOrEqualTo(1);
        JsonNode finished = finished(operator, executionId);
        assertThat(finished.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(restarts()).isEqualTo(1);
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private ResponseEntity<String> decide(TestUser user, String approvalId, String decision, String comment) {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        body.put("comment", comment);
        return post("/api/v1/approvals/" + approvalId + "/decision", user.token(), body);
    }

    /** The first approval of an execution, as the approver reads it now. */
    private JsonNode approvalOf(JsonNode execution) {
        JsonNode summary = read(get("/api/v1/executions/" + execution.get("executionId").asString(), admin.token()))
                .get("approvals").get(0);
        return read(get("/api/v1/approvals/" + summary.get("approvalId").asString(), approver.token()));
    }

    /** A question for the echo script, unique per test so the recorded model requests never mix. */
    private static String echo() {
        return "[APPROVAL-ECHO] restart demo-api " + uniqueName("q");
    }

    private JsonNode finished(TestUser user, String executionId) {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> read(get("/api/v1/executions/" + executionId, user.token())),
                        view -> !NOT_FINISHED.contains(view.get("status").asString()));
    }

    private long restarts() {
        return runtime.calls().stream().filter(call -> call.equals("restart:" + container)).count();
    }

    /** What the model was told about its calls, in the requests sent for the given marker. */
    private List<String> toolResults(String marker) {
        List<String> results = new ArrayList<>();
        llm.requestsFor(marker).getLast().messages().stream()
                .filter(LlmMessage.ToolResult.class::isInstance)
                .map(message -> ((LlmMessage.ToolResult) message).content())
                .forEach(results::add);
        return results;
    }

    /** Two different tasks released at the same instant; their results in order. */
    private static List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return first.call();
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(a.get(), b.get());
        }
    }

    private static <T> List<T> inParallel(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
