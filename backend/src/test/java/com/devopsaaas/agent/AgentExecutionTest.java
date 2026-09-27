package com.devopsaaas.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.environment.AutonomyLevel;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The state machine of docs/03-arquitetura.md, section 6.2: a terminal status is final. */
class AgentExecutionTest {

    private AgentExecution queued() {
        Conversation conversation = Conversation.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
        Message trigger = Message.user(conversation, 1, "hi");
        return AgentExecution.queued(conversation, trigger, conversation.getCreatedBy(), AutonomyLevel.ASSISTED,
                "m", "p", "{}", new AgentExecution.Limits(2, 3, 1_000), null, null);
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
        execution.countLlmIteration(10, 5);
        execution.countLlmIteration(10, 5);
        execution.countLlmIteration(10, 5);
        assertThat(execution.llmIterationsExhausted()).isTrue();
        assertThat(execution.getInputTokens()).isEqualTo(30);
        execution.addActiveTime(1_000);
        assertThat(execution.activeTimeExhausted()).isTrue();
        assertThat(execution.remainingActiveMs()).isZero();
    }
}
