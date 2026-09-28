package com.devopsaaas.tool.execution;

import com.devopsaaas.audit.AuditAction;
import com.devopsaaas.audit.AuditEntry;
import com.devopsaaas.audit.AuditOutcome;
import com.devopsaaas.audit.AuditRecorder;
import com.devopsaaas.audit.AuditResourceType;
import com.devopsaaas.tool.api.ToolErrorCode;
import com.devopsaaas.tool.policy.DenialReason;
import com.devopsaaas.tool.policy.PolicyContext;
import com.devopsaaas.tool.policy.PolicyDecision;
import com.devopsaaas.tool.policy.PolicyOutcome;
import com.devopsaaas.tool.policy.ToolProposal;
import com.devopsaaas.tool.registry.RegisteredTool;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.context.ApplicationEventPublisher;
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
    private static final String NO_IMPACT_DESCRIPTION = "The tool declares no impact description.";

    private final ToolExecutionRepository executions;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;
    private final OutputProcessor processor;
    private final ApplicationEventPublisher events;

    ToolExecutionJournal(ToolExecutionRepository executions, AuditRecorder audit, TransactionTemplate transactions,
            OutputProcessor processor, ApplicationEventPublisher events) {
        this.executions = executions;
        this.audit = audit;
        this.transactions = transactions;
        this.processor = processor;
        this.events = events;
    }

    /** Result of claiming an approved call under its row lock (slice 7). */
    sealed interface Claim {
    }

    /** The call is no longer waiting: another resumption took it, or it was cancelled, rejected or expired. */
    record NotWaiting() implements Claim {
    }

    record DeniedNow(ToolExecution execution) implements Claim {
    }

    record Claimed(ToolExecution execution, ToolExecutionRequest request, PolicyDecision decision) implements Claim {
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
            String impact = decision.tool().definition().impactDescription();
            // Same transaction: the approval is created with the call, or neither exists.
            events.publishEvent(new ToolCallAwaitingApproval(request.context().organizationId(),
                    request.agentExecutionId(), execution.getId(), request.context().requestedBy(),
                    execution.getToolName(), execution.getRiskLevel(), execution.getArgumentsHash(),
                    impact == null || impact.isBlank() ? NO_IMPACT_DESCRIPTION : impact, execution.getRationale()));
            return execution;
        });
    }

    /**
     * Slice 7: takes an approved call out of WAITING_APPROVAL under its row lock, exactly once. The hashes are
     * compared and the policy runs again with the state of now; only then is the call marked RUNNING, committed
     * before the tool is invoked. Nothing here asks the LLM anything.
     */
    Claim claimApproved(ApprovedCall call, UUID environmentId,
            BiFunction<PolicyContext, ToolProposal, PolicyDecision> policy) {
        return transactions.execute(status -> {
            Optional<ToolExecution> locked = executions.lockByIdAndOrganizationId(call.toolExecutionId(),
                    call.organizationId());
            if (locked.isEmpty() || locked.get().getStatus() != ToolExecutionStatus.WAITING_APPROVAL) {
                return new NotWaiting();
            }
            ToolExecution execution = locked.get();
            PolicyContext context = new PolicyContext(call.organizationId(), environmentId, call.requestedBy(), 1);
            ToolExecutionRequest request = new ToolExecutionRequest(context, execution.getAgentExecutionId(),
                    execution.getLlmCallId(), execution.getSeq(), new ToolProposal(execution.getToolName(),
                            execution.getArguments(), execution.getLlmToolCallId(), execution.getRationale()));
            if (!call.approvedArgumentsHash().equals(execution.getArgumentsHash())) {
                return deniedNow(request, execution, DenialReason.ARGUMENTS_MISMATCH,
                        "The recorded call no longer matches what was approved.");
            }
            PolicyDecision decision = policy.apply(context, request.proposal());
            if (decision.outcome() == PolicyOutcome.DENY) {
                return deniedNow(request, execution, decision.denialReason(),
                        OutputProcessor.cleanText(decision.detail(), MAX_TEXT_CHARS));
            }
            // The stored arguments, bound again, must produce the very hash the human approved.
            if (!call.approvedArgumentsHash().equals(decision.argumentsHash())) {
                return deniedNow(request, execution, DenialReason.ARGUMENTS_MISMATCH,
                        "The recorded arguments no longer match what was approved.");
            }
            execution.startApproved();
            audit.record(entry(request, execution, AuditAction.TOOL_EXECUTION_STARTED, AuditOutcome.SUCCESS)
                    .detail("approved", true));
            return new Claimed(execution, request, decision);
        });
    }

    private DeniedNow deniedNow(ToolExecutionRequest request, ToolExecution execution, DenialReason reason,
            String detail) {
        execution.deny(reason, detail);
        audit.record(entry(request, execution, AuditAction.TOOL_CALL_DENIED, AuditOutcome.DENIED)
                .detail("denialReason", reason)
                .detail("approved", true));
        return new DeniedNow(execution);
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
