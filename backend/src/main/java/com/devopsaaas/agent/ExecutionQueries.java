package com.devopsaaas.agent;

import com.devopsaaas.approval.ApprovalQueries;
import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.tool.execution.ToolCallRecord;
import com.devopsaaas.tool.execution.ToolExecutionHistory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads executions for the API, always within the caller's organization (another organization's is 404). */
@Service
public class ExecutionQueries {

    private final AgentExecutionRepository executions;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ToolExecutionHistory tools;
    private final EnvironmentDirectory environments;
    private final ApprovalQueries approvals;

    ExecutionQueries(AgentExecutionRepository executions, ConversationRepository conversations,
            MessageRepository messages, ToolExecutionHistory tools, EnvironmentDirectory environments,
            ApprovalQueries approvals) {
        this.executions = executions;
        this.conversations = conversations;
        this.messages = messages;
        this.tools = tools;
        this.environments = environments;
        this.approvals = approvals;
    }

    @Transactional(readOnly = true)
    public ExecutionView get(CurrentUser user, UUID executionId) {
        AgentExecution execution = executions.findByIdAndOrganizationId(executionId, user.organizationId())
                .orElseThrow(() -> ApiException.notFound("Execution not found."));
        return view(execution);
    }

    @Transactional(readOnly = true)
    public ExecutionView view(AgentExecution execution) {
        UUID organizationId = execution.getOrganizationId();
        Conversation conversation = conversations.findByIdAndOrganizationId(execution.getConversationId(),
                organizationId).orElseThrow();
        Map<UUID, String> serviceNames = environments.serviceNames(organizationId, conversation.getEnvironmentId());
        List<ExecutionView.Action> actions = tools.forAgentExecution(organizationId, execution.getId()).stream()
                .map(call -> action(call, serviceNames))
                .toList();
        ExecutionView.Answer answer = messages
                .findByAgentExecutionIdAndOrganizationIdAndRole(execution.getId(), organizationId, MessageRole.ASSISTANT)
                .map(message -> new ExecutionView.Answer(message.getContent(),
                        !"LLM_OUTPUT_TRUNCATED".equals(execution.getStatusReason())))
                .orElse(null);
        return new ExecutionView(execution.getId(), execution.getConversationId(), execution.getStatus(),
                execution.getStatusReason(), answer, actions,
                approvals.forExecution(organizationId, execution.getId()),
                new ExecutionView.Budget(execution.getToolCallCount(), execution.getMaxToolCalls(),
                        execution.getLlmIterationCount(), execution.getMaxLlmIterations(), execution.getActiveMs(),
                        execution.getMaxActiveMs()),
                new ExecutionView.Usage(execution.getInputTokens(), execution.getOutputTokens(),
                        execution.getEstimatedCostUsd()),
                execution.getLlmModel(), execution.getPromptVersion(), execution.getCreatedAt(),
                execution.getStartedAt(), execution.getFinishedAt());
    }

    private static ExecutionView.Action action(ToolCallRecord call, Map<UUID, String> serviceNames) {
        return new ExecutionView.Action(call.seq(), call.toolName(),
                call.targetServiceId() == null ? null : serviceNames.get(call.targetServiceId()),
                call.riskLevel(), call.status().name(),
                call.denialReason() == null ? null : call.denialReason().name(),
                call.errorCode() == null ? null : call.errorCode().name(),
                call.durationMs());
    }
}
