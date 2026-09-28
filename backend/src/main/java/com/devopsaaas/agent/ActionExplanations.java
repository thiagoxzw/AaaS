package com.devopsaaas.agent;

import com.devopsaaas.approval.ApprovalQueries;
import com.devopsaaas.audit.AuditEvent;
import com.devopsaaas.audit.AuditQuery;
import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.tool.execution.ToolCallRecord;
import com.devopsaaas.tool.execution.ToolExecutionHistory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Builds {@link ActionExplanation}, always within the caller's organization (another one's is 404). */
@Service
public class ActionExplanations {

    private static final int AUDIT_LINES = 200;

    private final ToolExecutionHistory tools;
    private final AgentExecutionRepository executions;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final EnvironmentDirectory environments;
    private final ApprovalQueries approvals;
    private final AuditQuery audit;
    private final JsonMapper json = JsonMapper.builder().build();

    ActionExplanations(ToolExecutionHistory tools, AgentExecutionRepository executions,
            ConversationRepository conversations, MessageRepository messages, EnvironmentDirectory environments,
            ApprovalQueries approvals, AuditQuery audit) {
        this.tools = tools;
        this.executions = executions;
        this.conversations = conversations;
        this.messages = messages;
        this.environments = environments;
        this.approvals = approvals;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ActionExplanation explain(CurrentUser user, UUID toolExecutionId) {
        UUID organizationId = user.organizationId();
        ToolCallRecord call = tools.find(organizationId, toolExecutionId)
                .orElseThrow(() -> ApiException.notFound("Tool execution not found."));
        AgentExecution execution = executions.findByIdAndOrganizationId(call.agentExecutionId(), organizationId)
                .orElseThrow(() -> ApiException.notFound("Tool execution not found."));
        Conversation conversation = conversations.findByIdAndOrganizationId(execution.getConversationId(),
                organizationId).orElseThrow();
        Message question = messages.findByIdAndOrganizationId(execution.getTriggerMessageId(), organizationId)
                .orElseThrow();
        Map<UUID, String> names = environments.serviceNames(organizationId, conversation.getEnvironmentId());

        List<ActionExplanation.Observation> observations = new ArrayList<>();
        for (ToolCallRecord earlier : tools.forAgentExecution(organizationId, execution.getId())) {
            if (earlier.seq() < call.seq()) {
                observations.add(new ActionExplanation.Observation(earlier.seq(), earlier.toolName(),
                        name(names, earlier.targetServiceId()), earlier.status().name(), findingCodes(earlier)));
            }
        }
        List<ActionExplanation.AuditLine> trail = audit.search(user, new AuditQuery.Filter(null, null, null, null,
                        null, call.id()), null, null, 0, AUDIT_LINES).stream()
                .map(ActionExplanations::line)
                .toList()
                .reversed();

        return new ActionExplanation(call.id(), execution.getId(), conversation.getId(),
                new ActionExplanation.Request(execution.getRequestedBy(), question.getContent(),
                        question.getCreatedAt()),
                new ActionExplanation.Action(call.seq(), call.toolName(), name(names, call.targetServiceId()),
                        call.riskLevel() == null ? null : call.riskLevel().name(), parse(call.arguments())),
                observations,
                new ActionExplanation.AgentClaims(call.rationale(), false),
                approvals.forToolExecution(organizationId, call.id()).orElse(null),
                new ActionExplanation.Result(call.status().name(),
                        call.denialReason() == null ? null : call.denialReason().name(),
                        call.errorCode() == null ? null : call.errorCode().name(), call.errorMessage(),
                        call.output() == null ? null : parse(call.output()), call.finishedAt(), call.durationMs()),
                trail);
    }

    private static ActionExplanation.AuditLine line(AuditEvent event) {
        return new ActionExplanation.AuditLine(event.getOccurredAt(), event.getAction().name(),
                event.getActorType().name(), event.getActorLabel(), event.getOutcome().name());
    }

    private List<String> findingCodes(ToolCallRecord call) {
        if (call.output() == null) {
            return List.of();
        }
        JsonNode output = parse(call.output());
        if (output == null) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        output.path("findings").forEach(finding -> codes.add(finding.path("code").asString("")));
        return codes;
    }

    private static String name(Map<UUID, String> names, UUID serviceId) {
        return serviceId == null ? null : names.get(serviceId);
    }

    private JsonNode parse(String value) {
        try {
            return value == null ? null : json.readTree(value);
        } catch (JacksonException exception) {
            return null;
        }
    }
}
