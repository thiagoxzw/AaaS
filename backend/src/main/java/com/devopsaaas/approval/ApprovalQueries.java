package com.devopsaaas.approval;

import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.tool.execution.ToolCallRecord;
import com.devopsaaas.tool.execution.ToolExecutionHistory;
import com.devopsaaas.tool.execution.ToolExecutionStatus;
import com.devopsaaas.tool.registry.RegisteredTool;
import com.devopsaaas.tool.registry.ToolRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads approvals for the API, always within the caller's organization (another organization's is 404). */
@Service
public class ApprovalQueries {

    private final ApprovalRepository approvals;
    private final ToolExecutionHistory tools;
    private final ToolRegistry registry;
    private final JsonMapper json = JsonMapper.builder().build();

    ApprovalQueries(ApprovalRepository approvals, ToolExecutionHistory tools, ToolRegistry registry) {
        this.approvals = approvals;
        this.tools = tools;
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public List<ApprovalView> list(CurrentUser user, Optional<ApprovalStatus> status) {
        List<Approval> found = status
                .map(value -> approvals.findAllByOrganizationIdAndStatusOrderByCreatedAtDesc(user.organizationId(),
                        value))
                .orElseGet(() -> approvals.findAllByOrganizationIdOrderByCreatedAtDesc(user.organizationId()));
        return found.stream().map(this::view).toList();
    }

    /** The approvals of one execution, oldest first, for the execution view. */
    public record ApprovalSummary(UUID approvalId, UUID toolExecutionId, ApprovalStatus status,
            Instant expiresAt) {
    }

    @Transactional(readOnly = true)
    public List<ApprovalSummary> forExecution(UUID organizationId, UUID agentExecutionId) {
        return approvals.findAllByAgentExecutionIdAndOrganizationIdOrderByCreatedAtAsc(agentExecutionId,
                        organizationId).stream()
                .map(approval -> new ApprovalSummary(approval.getId(), approval.getToolExecutionId(),
                        approval.getStatus(), approval.getExpiresAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ApprovalView get(CurrentUser user, UUID approvalId) {
        return approvals.findByIdAndOrganizationId(approvalId, user.organizationId())
                .map(this::view)
                .orElseThrow(() -> ApiException.notFound("Approval not found."));
    }

    @Transactional(readOnly = true)
    public ApprovalView view(Approval approval) {
        List<ToolCallRecord> calls = tools.forAgentExecution(approval.getOrganizationId(),
                approval.getAgentExecutionId());
        Map<UUID, ToolCallRecord> byId = calls.stream()
                .collect(Collectors.toMap(ToolCallRecord::id, Function.identity()));
        ToolCallRecord call = byId.get(approval.getToolExecutionId());
        JsonNode arguments = call == null ? null : parse(call.arguments());
        String target = call == null ? null : registry.find(call.toolName())
                .map(RegisteredTool::definition)
                .filter(definition -> definition.hasTarget() && arguments != null)
                .map(definition -> arguments.path(definition.targetParameter()).asString(null))
                .orElse(null);
        return new ApprovalView(approval.getId(), approval.getStatus(), approval.getCreatedAt(),
                approval.getExpiresAt(), approval.getAgentExecutionId(), approval.getToolExecutionId(),
                new ApprovalView.Action(call == null ? null : call.toolName(), target, arguments),
                new ApprovalView.SystemAssessment(approval.getRiskLevel().name(), approval.getImpactDescription(),
                        approval.getArgumentsHash(), evidence(calls)),
                ApprovalView.AgentClaims.of(approval.getAgentJustification()),
                approval.getDecidedAt() == null ? null : new ApprovalView.DecisionView(approval.getDecidedBy(),
                        approval.getDecidedAt(), approval.getDecisionComment()));
    }

    /**
     * The deterministic findings already observed in this execution (slice 5): facts computed by the backend
     * from the runtime, which the approver can hold the agent's justification against.
     */
    private List<ApprovalView.Evidence> evidence(List<ToolCallRecord> calls) {
        List<ApprovalView.Evidence> evidence = new ArrayList<>();
        for (ToolCallRecord call : calls) {
            if (call.status() != ToolExecutionStatus.SUCCEEDED || call.output() == null) {
                continue;
            }
            JsonNode output = parse(call.output());
            if (output == null) {
                continue;
            }
            for (JsonNode finding : output.path("findings")) {
                evidence.add(new ApprovalView.Evidence(finding.path("code").asString(null),
                        finding.path("severity").asString(null), finding.path("message").asString(null),
                        call.toolName(), call.finishedAt()));
            }
        }
        return evidence;
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (JacksonException exception) {
            return null;
        }
    }
}
