package com.devopsaaas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.environment.AutonomyLevel;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The state machine of docs/03-arquitetura.md, section 6.2: a terminal status is final. */
class AgentExecutionTest {

    private AgentExecution queued() {
        Conversation conversation = Conversation.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
        Message trigger = Message.user(conversation, 1, "hi");
        return AgentExecution.queued(conversation, trigger, conversation.getCreatedBy(), AutonomyLevel.ASSISTED,
                "m", "p", "{}", new AgentExecution.Limits(2, 3, 1_000), null, null);
    }

    /**
     * Slice 9a, the whole table: every status x every transition. Exactly the transitions of
     * docs/fatias/09a-evidencias.md are accepted; everything else, repetitions included, is refused.
     */
    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("transitionTable")
    void theTransitionTable(String transition, AgentExecutionStatus from, boolean allowed) {
        AgentExecution execution = in(from);
        Runnable apply = switch (transition) {
            case "start" -> execution::start;
            case "waitForApproval" -> execution::waitForApproval;
            case "resumeAfterApproval" -> execution::resumeAfterApproval;
            case "finish" -> () -> execution.finish(AgentExecutionStatus.COMPLETED, null);
            case "cancel" -> () -> execution.cancel("user");
            default -> throw new IllegalArgumentException(transition);
        };
        if (allowed) {
            apply.run();
            assertThat(execution.getStatus()).isNotEqualTo(from);
        } else {
            assertThatThrownBy(apply::run).isInstanceOf(IllegalStateException.class);
            assertThat(execution.getStatus()).isEqualTo(from);
        }
    }

    static Stream<Arguments> transitionTable() {
        Map<String, Set<AgentExecutionStatus>> allowedFrom = Map.of(
                "start", EnumSet.of(AgentExecutionStatus.QUEUED),
                "waitForApproval", EnumSet.of(AgentExecutionStatus.RUNNING),
                "resumeAfterApproval", EnumSet.of(AgentExecutionStatus.WAITING_APPROVAL),
                "finish", EnumSet.of(AgentExecutionStatus.RUNNING),
                "cancel", AgentExecutionStatus.ACTIVE);
        return allowedFrom.entrySet().stream().flatMap(entry -> Arrays.stream(AgentExecutionStatus.values())
                .map(from -> Arguments.of(entry.getKey(), from, entry.getValue().contains(from))));
    }

    /** An execution brought to the given status through valid transitions only. */
    private AgentExecution in(AgentExecutionStatus status) {
        AgentExecution execution = queued();
        switch (status) {
            case QUEUED -> { }
            case CANCELLED -> execution.cancel("user");
            default -> {
                execution.start();
                if (status == AgentExecutionStatus.WAITING_APPROVAL) {
                    execution.waitForApproval();
                } else if (status != AgentExecutionStatus.RUNNING) {
                    execution.finish(status, "test");
                }
            }
        }
        assertThat(execution.getStatus()).isEqualTo(status);
        return execution;
    }

    @Test
    void aTerminalStatus_isFinal() {
        AgentExecution execution = queued();
        execution.start();
        execution.finish(AgentExecutionStatus.COMPLETED, null);

        assertThatThrownBy(execution::start).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.finish(AgentExecutionStatus.FAILED, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.cancel("x")).isInstanceOf(IllegalStateException.class);
        assertThat(execution.getStatus()).isEqualTo(AgentExecutionStatus.COMPLETED);
    }

    @Test
    void finish_onlyAcceptsTerminalStatuses() {
        AgentExecution execution = queued();
        execution.start();

        assertThatThrownBy(() -> execution.finish(AgentExecutionStatus.WAITING_APPROVAL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyActiveStatus_canBeCancelled() {
        AgentExecution queued = queued();
        queued.cancel("user");
        AgentExecution waiting = queued();
        waiting.start();
        waiting.waitForApproval();
        waiting.cancel("user");

        assertThat(queued.getStatus()).isEqualTo(AgentExecutionStatus.CANCELLED);
        assertThat(waiting.getStatus()).isEqualTo(AgentExecutionStatus.CANCELLED);
        assertThat(waiting.getFinishedAt()).isNotNull();
    }

    @Test
    void budgets_countEveryProposal_andEveryIteration() {
        AgentExecution execution = queued();
        execution.start();

        assertThat(execution.countToolCall()).isZero();
        assertThat(execution.countToolCall()).isEqualTo(1);
        assertThat(execution.countToolCall()).as("counted even beyond the limit").isEqualTo(2);
        execution.countLlmIteration(10, 5, new java.math.BigDecimal("0.25"));
        execution.countLlmIteration(10, 5, new java.math.BigDecimal("0.25"));
        execution.countLlmIteration(10, 5, new java.math.BigDecimal("0.25"));
        assertThat(execution.llmIterationsExhausted()).isTrue();
        assertThat(execution.getInputTokens()).isEqualTo(30);
        assertThat(execution.getEstimatedCostUsd()).isEqualByComparingTo("0.75");
        execution.addActiveTime(1_000);
        assertThat(execution.activeTimeExhausted()).isTrue();
        assertThat(execution.remainingActiveMs()).isZero();
    }
}
