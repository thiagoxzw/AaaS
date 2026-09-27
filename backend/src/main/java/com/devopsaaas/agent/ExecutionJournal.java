package com.devopsaaas.agent;

import com.devopsaaas.audit.AuditAction;
import com.devopsaaas.audit.AuditEntry;
import com.devopsaaas.audit.AuditOutcome;
import com.devopsaaas.audit.AuditRecorder;
import com.devopsaaas.audit.AuditResourceType;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.tool.execution.ToolExecutionHistory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every state change of an execution, each in its own short transaction and under the execution's row lock.
 * The worker checks the status inside that lock at every step, so a cancellation that commits between two
 * steps is always seen, and the step in progress still ends up recorded (cooperative cancellation).
 */
@Component
class ExecutionJournal {

    /** Result of a checkpoint between two steps of the loop. */
    enum Checkpoint {
        CONTINUE, STOPPED
    }

    /** A counted tool call: its sequence number and the budget left for the policy. */
    record ToolSlot(int seq, int remainingToolCalls, boolean overBudget) {
    }

    record RecordedLlmCall(UUID id, boolean stillRunning) {
    }

    record LlmCallData(String model, LlmCallOutcome outcome, String text, int inputTokens, int outputTokens,
            int durationMs, String errorCode) {
    }

    private final AgentExecutionRepository executions;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final LlmCallRepository llmCalls;
    private final ToolExecutionHistory tools;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;

    ExecutionJournal(AgentExecutionRepository executions, ConversationRepository conversations,
            MessageRepository messages, LlmCallRepository llmCalls, ToolExecutionHistory tools, AuditRecorder audit,
            TransactionTemplate transactions, MeterRegistry meters) {
        this.executions = executions;
        this.conversations = conversations;
        this.messages = messages;
        this.llmCalls = llmCalls;
        this.tools = tools;
        this.audit = audit;
        this.transactions = transactions;
        this.meters = meters;
    }

    /** QUEUED → RUNNING. Empty when the execution is no longer queued (cancelled meanwhile, or already taken). */
    Optional<ExecutionState> start(ExecutionRef ref) {
        return transactions.execute(status -> {
            AgentExecution execution = lock(ref);
            if (execution.getStatus() != AgentExecutionStatus.QUEUED) {
                return Optional.empty();
            }
            execution.start();
            Conversation conversation = conversations
                    .findByIdAndOrganizationId(execution.getConversationId(), ref.organizationId())
                    .orElseThrow();
            int triggerSeq = messages.findByIdAndOrganizationId(execution.getTriggerMessageId(), ref.organizationId())
                    .orElseThrow().getSeq();
            audit.record(agent(execution, AuditAction.AGENT_EXECUTION_STARTED, AuditOutcome.SUCCESS));
            return Optional.of(new ExecutionState(execution.getId(), execution.getOrganizationId(),
                    conversation.getId(), conversation.getEnvironmentId(), execution.getRequestedBy(), triggerSeq,
                    execution.getContextSnapshot()));
        });
    }

    /**
     * Between steps: adds the active time, stops if the execution is no longer RUNNING, and ends it with
     * BUDGET_EXCEEDED when the iteration or time budget is spent (RF-26).
     */
    Checkpoint checkpoint(ExecutionRef ref, long activeMillis) {
        return transactions.execute(status -> {
            AgentExecution execution = lock(ref);
            if (execution.getStatus() != AgentExecutionStatus.RUNNING) {
                return Checkpoint.STOPPED;
            }
            execution.addActiveTime(activeMillis);
            if (execution.llmIterationsExhausted()) {
                close(execution, AgentExecutionStatus.BUDGET_EXCEEDED, "MAX_LLM_ITERATIONS");
                return Checkpoint.STOPPED;
            }
            if (execution.activeTimeExhausted()) {
                close(execution, AgentExecutionStatus.BUDGET_EXCEEDED, "MAX_ACTIVE_TIME");
                return Checkpoint.STOPPED;
            }
            return Checkpoint.CONTINUE;
        });
    }

    long remainingActiveMs(ExecutionRef ref) {
        return transactions.execute(status -> executions
                .findByIdAndOrganizationId(ref.executionId(), ref.organizationId())
                .map(AgentExecution::remainingActiveMs)
                .orElse(0L));
    }

    /**
     * Records a finished model call, whatever the execution's status is now: a call that completed after a
     * cancellation is still part of the history.
     */
    RecordedLlmCall recordLlmCall(ExecutionRef ref, LlmCallData data, long activeMillis) {
        return transactions.execute(status -> {
            AgentExecution execution = lock(ref);
            execution.addActiveTime(activeMillis);
            int seq = execution.countLlmIteration(data.inputTokens(), data.outputTokens());
            LlmCall call = llmCalls.save(LlmCall.record(ref.organizationId(), ref.executionId(), seq, data.model(),
                    data.outcome(), data.text(), data.inputTokens(), data.outputTokens(), data.durationMs(),
                    data.errorCode()));
            return new RecordedLlmCall(call.getId(), execution.getStatus() == AgentExecutionStatus.RUNNING);
        });
    }

    /**
     * Counts a proposal before the policy evaluates it (so invalid or denied proposals spend the budget too).
     * Empty when the execution was stopped meanwhile.
     */
    Optional<ToolSlot> countToolCall(ExecutionRef ref) {
        return transactions.execute(status -> {
            AgentExecution execution = lock(ref);
            if (execution.getStatus() != AgentExecutionStatus.RUNNING) {
                return Optional.empty();
            }
            int before = execution.countToolCall();
            return Optional.of(new ToolSlot(before + 1, execution.getMaxToolCalls() - before,
                    before >= execution.getMaxToolCalls()));
        });
    }

    void waitForApproval(ExecutionRef ref) {
        transactions.executeWithoutResult(status -> {
            AgentExecution execution = lock(ref);
            if (execution.getStatus() == AgentExecutionStatus.RUNNING) {
                execution.waitForApproval();
                audit.record(agent(execution, AuditAction.AGENT_EXECUTION_WAITING_APPROVAL, AuditOutcome.SUCCESS));
            }
        });
    }

    /** RUNNING → COMPLETED, with the agent's answer as the ASSISTANT message. */
    boolean complete(ExecutionRef ref, String answer, boolean truncated) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            AgentExecution execution = lock(ref);
            if (execution.getStatus() != AgentExecutionStatus.RUNNING) {
                return false;
            }
            int seq = messages.maxSeq(execution.getConversationId(), ref.organizationId()) + 1;
            messages.save(Message.assistant(ref.organizationId(), execution.getConversationId(), execution.getId(),
                    seq, answer));
            close(execution, AgentExecutionStatus.COMPLETED, truncated ? "LLM_OUTPUT_TRUNCATED" : null);
            return true;
        }));
    }

    /** RUNNING → FAILED or BUDGET_EXCEEDED. No-op if the execution already left RUNNING. */
    void finish(ExecutionRef ref, AgentExecutionStatus terminal, String reason) {
        transactions.executeWithoutResult(status -> {
            AgentExecution execution = lock(ref);
            if (execution.getStatus() == AgentExecutionStatus.RUNNING) {
                close(execution, terminal, reason);
            }
        });
    }

    /**
     * RF-23, by the requester. Idempotent: cancelling a finished execution returns it as it is. Calls waiting
     * for approval are cancelled with it; a step in progress finishes and is recorded.
     */
    AgentExecution cancel(CurrentUser user, UUID executionId) {
        return transactions.execute(status -> {
            AgentExecution execution = executions.lockByIdAndOrganizationId(executionId, user.organizationId())
                    .orElseThrow(() -> ApiException.notFound("Execution not found."));
            if (!execution.getRequestedBy().equals(user.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Only the user who started the execution can cancel it.");
            }
            if (execution.getStatus().isActive()) {
                execution.cancel("CANCELLED_BY_USER");
                tools.cancelAwaitingApproval(user.organizationId(), execution.getId(), execution.getRequestedBy());
                audit.record(AuditEntry.byUser(user, AuditAction.AGENT_EXECUTION_CANCELLED,
                                AuditResourceType.EXECUTION, execution.getId())
                        .agentExecutionId(execution.getId()));
                count(execution);
            }
            return execution;
        });
    }

    /** Startup recovery (RNF-CONF-09): executions a crash left RUNNING cannot continue. */
    int interruptRunning() {
        Integer interrupted = transactions.execute(status -> {
            List<AgentExecution> running = executions.findAllByStatusOrderByCreatedAtAsc(AgentExecutionStatus.RUNNING);
            for (AgentExecution execution : running) {
                execution.finish(AgentExecutionStatus.INTERRUPTED, "BACKEND_RESTARTED");
                audit.record(AuditEntry.bySystem(execution.getOrganizationId(),
                                AuditAction.AGENT_EXECUTION_INTERRUPTED, AuditResourceType.EXECUTION, execution.getId())
                        .outcome(AuditOutcome.FAILURE)
                        .agentExecutionId(execution.getId()));
                count(execution);
            }
            return running.size();
        });
        return interrupted == null ? 0 : interrupted;
    }

    List<ExecutionRef> queued() {
        return transactions.execute(status -> executions
                .findAllByStatusOrderByCreatedAtAsc(AgentExecutionStatus.QUEUED).stream()
                .map(execution -> new ExecutionRef(execution.getId(), execution.getOrganizationId()))
                .toList());
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private AgentExecution lock(ExecutionRef ref) {
        return executions.lockByIdAndOrganizationId(ref.executionId(), ref.organizationId())
                .orElseThrow(() -> new IllegalStateException("Execution " + ref.executionId() + " not found"));
    }

    private void close(AgentExecution execution, AgentExecutionStatus terminal, String reason) {
        execution.finish(terminal, reason);
        AuditAction action = switch (terminal) {
            case COMPLETED -> AuditAction.AGENT_EXECUTION_COMPLETED;
            case BUDGET_EXCEEDED -> AuditAction.AGENT_EXECUTION_BUDGET_EXCEEDED;
            default -> AuditAction.AGENT_EXECUTION_FAILED;
        };
        AuditEntry entry = agent(execution, action,
                terminal == AgentExecutionStatus.COMPLETED ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                .detail("toolCalls", execution.getToolCallCount())
                .detail("llmIterations", execution.getLlmIterationCount())
                .detail("activeMs", execution.getActiveMs());
        if (reason != null) {
            entry.detail("reason", reason);
        }
        audit.record(entry);
        count(execution);
    }

    private void count(AgentExecution execution) {
        meters.counter("devops.agent.executions", "status", execution.getStatus().name()).increment();
    }

    /** The agent acts on behalf of the requester (doc 04, 4.11). */
    private static AuditEntry agent(AgentExecution execution, AuditAction action, AuditOutcome outcome) {
        return AuditEntry.byAgentOnBehalfOf(execution.getOrganizationId(), execution.getRequestedBy(), action,
                        AuditResourceType.EXECUTION, execution.getId())
                .outcome(outcome)
                .agentExecutionId(execution.getId());
    }
}
