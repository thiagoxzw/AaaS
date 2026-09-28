package com.devopsaaas.tool.execution;

import com.devopsaaas.audit.AuditAction;
import com.devopsaaas.audit.AuditEntry;
import com.devopsaaas.audit.AuditOutcome;
import com.devopsaaas.audit.AuditRecorder;
import com.devopsaaas.audit.AuditResourceType;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The recorded tool calls, for other modules: reads scoped by organization, and the two state changes that
 * belong to an execution's lifecycle (cancellation and crash recovery). The history itself is the source of
 * truth (ADR-0009); nobody keeps a parallel list of "actions".
 */
@Service
public class ToolExecutionHistory {

    private final ToolExecutionRepository executions;
    private final AuditRecorder audit;

    ToolExecutionHistory(ToolExecutionRepository executions, AuditRecorder audit) {
        this.executions = executions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ToolCallRecord> forAgentExecution(UUID organizationId, UUID agentExecutionId) {
        return executions.findAllByAgentExecutionIdAndOrganizationIdOrderBySeqAsc(agentExecutionId, organizationId)
                .stream().map(ToolCallRecord::of).toList();
    }

    /** Calls of several executions, newest first. */
    @Transactional(readOnly = true)
    public List<ToolCallRecord> forAgentExecutions(UUID organizationId, Collection<UUID> agentExecutionIds) {
        if (agentExecutionIds.isEmpty()) {
            return List.of();
        }
        return executions.findAllByOrganizationIdAndAgentExecutionIdIn(organizationId, agentExecutionIds).stream()
                .sorted(Comparator.comparing(ToolExecution::getCreatedAt).reversed())
                .map(ToolCallRecord::of)
                .toList();
    }

    /** Within the caller's transaction: the calls of a cancelled execution that were waiting for approval. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelAwaitingApproval(UUID organizationId, UUID agentExecutionId, UUID requestedBy) {
        List<ToolExecution> waiting = executions.findAllByAgentExecutionIdAndOrganizationIdAndStatus(
                agentExecutionId, organizationId, ToolExecutionStatus.WAITING_APPROVAL);
        for (ToolExecution execution : waiting) {
            execution.cancelWhileWaiting();
            audit.record(AuditEntry.byAgentOnBehalfOf(organizationId, requestedBy, AuditAction.TOOL_CALL_CANCELLED,
                            AuditResourceType.TOOL_EXECUTION, execution.getId())
                    .toolName(execution.getToolName())
                    .agentExecutionId(agentExecutionId)
                    .toolExecutionId(execution.getId()));
        }
        return waiting.size();
    }

    /**
     * Slice 7, within the approval's transaction: a human rejected the call, or nobody decided in time. Only a
     * call still WAITING_APPROVAL changes; the approval's own audit event records the decision.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean closeWaiting(UUID organizationId, UUID toolExecutionId, boolean rejected) {
        return executions.lockByIdAndOrganizationId(toolExecutionId, organizationId)
                .filter(execution -> execution.getStatus() == ToolExecutionStatus.WAITING_APPROVAL)
                .map(execution -> {
                    if (rejected) {
                        execution.reject();
                    } else {
                        execution.expire();
                    }
                    return true;
                })
                .orElse(false);
    }

    /**
     * Startup recovery (RNF-CONF-09): a call left RUNNING by a crash may or may not have reached the runtime,
     * so it becomes OUTCOME_UNKNOWN, never FAILED or SUCCEEDED.
     */
    @Transactional
    public int recoverInterrupted() {
        List<ToolExecution> running = executions.findAllByStatus(ToolExecutionStatus.RUNNING);
        for (ToolExecution execution : running) {
            execution.markOutcomeUnknown("The backend stopped while the call was running.");
            audit.record(AuditEntry.bySystem(execution.getOrganizationId(),
                            AuditAction.TOOL_EXECUTION_OUTCOME_UNKNOWN, AuditResourceType.TOOL_EXECUTION,
                            execution.getId())
                    .outcome(AuditOutcome.FAILURE)
                    .toolName(execution.getToolName())
                    .agentExecutionId(execution.getAgentExecutionId())
                    .toolExecutionId(execution.getId()));
        }
        return running.size();
    }
}
