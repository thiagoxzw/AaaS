package com.devopsaaas.tool.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.policy.DenialReason;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Slice 9a, finding 9a-03 (closed): the tool call guards its own state machine, like AgentExecution and
 * Approval. docs/fatias/09a-evidencias.md, section 1, is the specification of this table.
 */
class ToolExecutionTest {

    private static final Map<String, Consumer<ToolExecution>> TRANSITIONS = Map.of(
            "deny", call -> call.deny(DenialReason.INVALID_ARGUMENTS, "no"),
            "awaitApproval", ToolExecution::awaitApproval,
            "rejectForCapacity", call -> call.rejectForCapacity("busy"),
            "start", ToolExecution::start,
            "finish", call -> call.finish(ToolExecutionStatus.SUCCEEDED, 1, "{}", false, 0, null, null),
            "markOutcomeUnknown", call -> call.markOutcomeUnknown("crash"),
            "startApproved", ToolExecution::startApproved,
            "reject", ToolExecution::reject,
            "expire", ToolExecution::expire,
            "cancelWhileWaiting", ToolExecution::cancelWhileWaiting);

    private static final Map<String, Set<ToolExecutionStatus>> ALLOWED_FROM = Map.of(
            "deny", EnumSet.of(ToolExecutionStatus.PROPOSED, ToolExecutionStatus.WAITING_APPROVAL),
            "awaitApproval", EnumSet.of(ToolExecutionStatus.PROPOSED),
            "rejectForCapacity", EnumSet.of(ToolExecutionStatus.PROPOSED),
            "start", EnumSet.of(ToolExecutionStatus.PROPOSED),
            "finish", EnumSet.of(ToolExecutionStatus.RUNNING),
            "markOutcomeUnknown", EnumSet.of(ToolExecutionStatus.RUNNING),
            "startApproved", EnumSet.of(ToolExecutionStatus.WAITING_APPROVAL),
            "reject", EnumSet.of(ToolExecutionStatus.WAITING_APPROVAL),
            "expire", EnumSet.of(ToolExecutionStatus.WAITING_APPROVAL),
            "cancelWhileWaiting", EnumSet.of(ToolExecutionStatus.WAITING_APPROVAL));

    /** Every status x every transition (11 x 10): exactly the transitions of the specification pass. */
    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("transitionTable")
    void theTransitionTable(String transition, ToolExecutionStatus from, boolean allowed) {
        ToolExecution call = in(from);
        if (allowed) {
            TRANSITIONS.get(transition).accept(call);
            assertThat(call.getStatus()).isNotEqualTo(from);
        } else {
            Instant finishedAt = call.getFinishedAt();
            assertThatThrownBy(() -> TRANSITIONS.get(transition).accept(call))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(call.getStatus()).isEqualTo(from);
            assertThat(call.getFinishedAt()).isEqualTo(finishedAt);
        }
    }

    static Stream<Arguments> transitionTable() {
        return ALLOWED_FROM.entrySet().stream().flatMap(entry -> Arrays.stream(ToolExecutionStatus.values())
                .map(from -> Arguments.of(entry.getKey(), from, entry.getValue().contains(from))));
    }

    /** OUTCOME_UNKNOWN is final: nothing can turn it into a success, a failure, a new run or anything else. */
    @Test
    void anUnknownOutcome_acceptsNoTransitionAtAll() {
        ToolExecution call = in(ToolExecutionStatus.OUTCOME_UNKNOWN);
        String message = call.getErrorMessage();

        for (Consumer<ToolExecution> transition : TRANSITIONS.values()) {
            assertThatThrownBy(() -> transition.accept(call)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(call.getStatus()).isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(call.getErrorMessage()).isEqualTo(message);
    }

    /** A running call ends only as SUCCEEDED, FAILED, TIMED_OUT or OUTCOME_UNKNOWN. */
    @Test
    void aRunningCall_endsOnlyInARunOutcome() {
        for (ToolExecutionStatus notAnOutcome : List.of(ToolExecutionStatus.PROPOSED, ToolExecutionStatus.DENIED,
                ToolExecutionStatus.WAITING_APPROVAL, ToolExecutionStatus.REJECTED, ToolExecutionStatus.EXPIRED,
                ToolExecutionStatus.CANCELLED, ToolExecutionStatus.RUNNING)) {
            ToolExecution call = in(ToolExecutionStatus.RUNNING);
            assertThatThrownBy(() -> call.finish(notAnOutcome, 1, null, false, 0, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(call.getStatus()).isEqualTo(ToolExecutionStatus.RUNNING);
        }
    }

    /** The timestamps follow the transitions, and a refused repetition does not touch them. */
    @Test
    void theInvariants_holdAlongTheWay() {
        ToolExecution call = in(ToolExecutionStatus.PROPOSED);
        assertThat(call.getStartedAt()).isNull();
        assertThat(call.getFinishedAt()).isNull();

        call.start();
        assertThat(call.getStartedAt()).isNotNull();
        assertThat(call.getFinishedAt()).isNull();

        call.finish(ToolExecutionStatus.FAILED, 2, null, false, 0, ToolErrorCode.TARGET_NOT_FOUND, "gone");
        Instant finishedAt = call.getFinishedAt();
        assertThat(finishedAt).isNotNull();
        assertThat(call.getDurationMs()).isNotNull().isNotNegative();
        assertThat(call.getAttemptCount()).isEqualTo(2);

        assertThatThrownBy(() -> call.finish(ToolExecutionStatus.SUCCEEDED, 3, "{}", false, 0, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(call.getStatus()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(call.getOutput()).isNull();
        assertThat(call.getAttemptCount()).isEqualTo(2);
        assertThat(call.getFinishedAt()).isEqualTo(finishedAt);

        ToolExecution approved = in(ToolExecutionStatus.WAITING_APPROVAL);
        assertThat(approved.getStartedAt()).as("waiting is not running").isNull();
        approved.startApproved();
        assertThat(approved.getStartedAt()).isNotNull();
    }

    /** A call brought to the given status through valid transitions only. */
    private static ToolExecution in(ToolExecutionStatus status) {
        ToolExecution call = ToolExecution.proposed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "call-1", "restartContainer", 1, RiskLevel.HIGH_RISK, UUID.randomUUID(), "{}", "hash", null, 0);
        switch (status) {
            case PROPOSED -> { }
            case DENIED -> call.deny(DenialReason.INVALID_ARGUMENTS, "no");
            case WAITING_APPROVAL -> call.awaitApproval();
            case REJECTED -> {
                call.awaitApproval();
                call.reject();
            }
            case EXPIRED -> {
                call.awaitApproval();
                call.expire();
            }
            case CANCELLED -> {
                call.awaitApproval();
                call.cancelWhileWaiting();
            }
            case RUNNING -> call.start();
            default -> {
                call.start();
                call.finish(status, 1, null, false, 0, null, "done");
            }
        }
        assertThat(call.getStatus()).isEqualTo(status);
        return call;
    }
}
