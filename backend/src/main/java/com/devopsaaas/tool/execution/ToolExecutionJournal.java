package com.devopsaaas.tool.execution;

import com.devopsaaas.audit.AuditAction;
import com.devopsaaas.audit.AuditEntry;
import com.devopsaaas.audit.AuditOutcome;
import com.devopsaaas.audit.AuditRecorder;
import com.devopsaaas.audit.AuditResourceType;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.policy.PolicyDecision;
import com.devopsaaas.tool.registry.RegisteredTool;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists every step of a tool call together with its audit event, each in its own short transaction. No
 * transaction is ever open while a tool talks to an external system.
 */
@Component
class ToolExecutionJournal {

    private static final int MAX_TOOL_NAME_CHARS = 100;
    private static final int MAX_TEXT_CHARS = 2000;
    private static final int MAX_CALL_ID_CHARS = 200;

    private final ToolExecutionRepository executions;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;
    private final OutputProcessor processor;

    ToolExecutionJournal(ToolExecutionRepository executions, AuditRecorder audit, TransactionTemplate transactions,
            OutputProcessor processor) {
        this.executions = executions;
        this.audit = audit;
        this.transactions = transactions;
        this.processor = processor;
    }

    ToolExecution denied(ToolExecutionRequest request, PolicyDecision decision) {
        return transactions.execute(status -> {
            ToolExecution execution = newExecution(request, decision);
            execution.deny(decision.denialReason(), OutputProcessor.cleanText(decision.detail(), MAX_TEXT_CHARS));
            executions.save(execution);
            audit.record(entry(request, execution, AuditAction.TOOL_CALL_DENIED, AuditOutcome.DENIED)
                    .detail("denialReason", decision.denialReason()));
            return execution;
        });
    }

    ToolExecution awaitingApproval(ToolExecutionRequest request, PolicyDecision decision) {
        return transactions.execute(status -> {
            ToolExecution execution = newExecution(request, decision);
            execution.awaitApproval();
            executions.save(execution);
            audit.record(entry(request, execution, AuditAction.TOOL_CALL_AWAITING_APPROVAL, AuditOutcome.SUCCESS));
            return execution;
        });
    }

    ToolExecution rejectedForCapacity(ToolExecutionRequest request, PolicyDecision decision) {
        return transactions.execute(status -> {
            ToolExecution execution = newExecution(request, decision);
            execution.rejectForCapacity("No execution slot became available before the tool's timeout.");
            executions.save(execution);
            audit.record(entry(request, execution, AuditAction.TOOL_EXECUTION_FAILED, AuditOutcome.FAILURE)
                    .detail("errorCode", ToolErrorCode.CAPACITY_EXCEEDED));
            return execution;
        });
    }

    ToolExecution started(ToolExecutionRequest request, PolicyDecision decision) {
        return transactions.execute(status -> {
            ToolExecution execution = newExecution(request, decision);
            execution.start();
            executions.save(execution);
            audit.record(entry(request, execution, AuditAction.TOOL_EXECUTION_STARTED, AuditOutcome.SUCCESS));
            return execution;
        });
    }

    ToolExecution finished(ToolExecutionRequest request, UUID executionId, ToolExecutionStatus finalStatus,
            int attempts, OutputProcessor.Processed output, ToolErrorCode errorCode, String errorMessage) {
        return transactions.execute(status -> {
            ToolExecution execution = executions
                    .findByIdAndOrganizationId(executionId, request.context().organizationId())
                    .orElseThrow();
            execution.finish(finalStatus, attempts,
                    output == null ? null : output.json(),
                    output != null && output.truncated(),
                    output == null ? 0 : output.redactions(),
                    errorCode,
                    OutputProcessor.cleanText(errorMessage, MAX_TEXT_CHARS));
            AuditEntry entry = entry(request, execution, actionFor(finalStatus),
                    finalStatus == ToolExecutionStatus.SUCCEEDED ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                    .detail("attempts", attempts)
                    .detail("durationMs", execution.getDurationMs());
            if (errorCode != null) {
                entry.detail("errorCode", errorCode);
            }
            audit.record(entry);
            return execution;
        });
    }

    private ToolExecution newExecution(ToolExecutionRequest request, PolicyDecision decision) {
        RegisteredTool tool = decision.registeredTool().orElse(null);
        OutputProcessor.Processed arguments = decision.canonicalArguments() != null
                ? processor.processArguments(decision.canonicalArguments())
                : processor.processRawArguments(request.proposal().argumentsJson());
        String toolName = tool != null ? tool.name()
                : OutputProcessor.cleanText(String.valueOf(request.proposal().toolName()), MAX_TOOL_NAME_CHARS);
        return ToolExecution.proposed(
                request.context().organizationId(),
                request.agentExecutionId(),
                request.llmCallId(),
                request.seq(),
                OutputProcessor.cleanText(request.proposal().llmToolCallId(), MAX_CALL_ID_CHARS),
                toolName,
                tool == null ? null : tool.definition().version(),
                tool == null ? null : tool.definition().riskLevel(),
                decision.resolvedTarget().map(target -> target.serviceId()).orElse(null),
                arguments.json(),
                decision.argumentsHash(),
                OutputProcessor.cleanText(request.proposal().rationale(), MAX_TEXT_CHARS),
                arguments.redactions());
    }

    /** Actor AGENT, on behalf of the requesting user; the resource is the target service when there is one. */
    private static AuditEntry entry(ToolExecutionRequest request, ToolExecution execution, AuditAction action,
            AuditOutcome outcome) {
        boolean hasTarget = execution.getTargetServiceId() != null;
        AuditEntry entry = AuditEntry.byAgentOnBehalfOf(
                        request.context().organizationId(),
                        request.context().requestedBy(),
                        action,
                        hasTarget ? AuditResourceType.SERVICE : AuditResourceType.TOOL_EXECUTION,
                        hasTarget ? execution.getTargetServiceId() : execution.getId())
                .toolName(execution.getToolName())
                .agentExecutionId(execution.getAgentExecutionId())
                .toolExecutionId(execution.getId())
                .outcome(outcome)
                .detail("status", execution.getStatus());
        if (execution.getRiskLevel() != null) {
            entry.detail("riskLevel", execution.getRiskLevel());
        }
        return entry;
    }

    private static AuditAction actionFor(ToolExecutionStatus status) {
        return switch (status) {
            case SUCCEEDED -> AuditAction.TOOL_EXECUTION_SUCCEEDED;
            case TIMED_OUT -> AuditAction.TOOL_EXECUTION_TIMED_OUT;
            case OUTCOME_UNKNOWN -> AuditAction.TOOL_EXECUTION_OUTCOME_UNKNOWN;
            default -> AuditAction.TOOL_EXECUTION_FAILED;
        };
    }
}
