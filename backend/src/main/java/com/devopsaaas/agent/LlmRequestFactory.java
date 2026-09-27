package com.devopsaaas.agent;

import com.devopsaaas.llm.LlmMessage;
import com.devopsaaas.llm.LlmProperties;
import com.devopsaaas.llm.LlmRequest;
import com.devopsaaas.llm.LlmToolCall;
import com.devopsaaas.llm.LlmToolSpec;
import com.devopsaaas.shared.security.Permission;
import com.devopsaaas.tool.execution.ToolCallRecord;
import com.devopsaaas.tool.execution.ToolExecutionHistory;
import com.devopsaaas.tool.policy.ToolCatalog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rebuilds, for every turn, what the model sees, from the records alone: the context snapshot, the
 * conversation window, and this execution's LLM calls and tool calls. Nothing is kept in memory between
 * turns, so an execution resumed later (slice 7) gets the same history.
 *
 * <p>Tool results are the executor's cleaned output (sanitized, masked, bounded); a denied call only says
 * that it was denied and why.
 */
@Component
class LlmRequestFactory {

    private final MessageRepository messages;
    private final LlmCallRepository llmCalls;
    private final ToolExecutionHistory tools;
    private final ToolCatalog catalog;
    private final LlmProperties llm;
    private final AgentProperties agent;
    private final JsonMapper json;

    LlmRequestFactory(MessageRepository messages, LlmCallRepository llmCalls, ToolExecutionHistory tools,
            ToolCatalog catalog, LlmProperties llm, AgentProperties agent, JsonMapper json) {
        this.messages = messages;
        this.llmCalls = llmCalls;
        this.tools = tools;
        this.catalog = catalog;
        this.llm = llm;
        this.agent = agent;
        this.json = json;
    }

    /** Empty when the environment is no longer available. */
    @Transactional(readOnly = true)
    Optional<LlmRequest> build(ExecutionState state, Set<Permission> currentPermissions, Duration remainingActive) {
        Optional<List<ToolCatalog.Entry>> offered =
                catalog.visibleTo(state.organizationId(), currentPermissions, state.environmentId());
        if (offered.isEmpty()) {
            return Optional.empty();
        }
        List<LlmToolSpec> specs = offered.get().stream()
                .map(entry -> new LlmToolSpec(entry.tool().name(), entry.tool().definition().description(),
                        entry.tool().inputSchema()))
                .toList();

        List<LlmMessage> history = new ArrayList<>();
        int fromSeq = Math.max(1, state.triggerSeq() - agent.conversationWindow() + 1);
        for (Message message : messages.findAllByConversationIdAndOrganizationIdAndSeqBetweenOrderBySeqAsc(
                state.conversationId(), state.organizationId(), fromSeq, state.triggerSeq())) {
            history.add(message.getRole() == MessageRole.USER
                    ? new LlmMessage.User(message.getContent())
                    : new LlmMessage.Assistant(message.getContent(), List.of()));
        }

        Map<UUID, List<ToolCallRecord>> callsByTurn = tools.forAgentExecution(state.organizationId(), state.id())
                .stream()
                .collect(Collectors.groupingBy(ToolCallRecord::llmCallId, LinkedHashMap::new, Collectors.toList()));
        for (LlmCall call : llmCalls.findAllByAgentExecutionIdAndOrganizationIdOrderBySeqAsc(state.id(),
                state.organizationId())) {
            if (call.getFinishReason() == LlmCallOutcome.ERROR) {
                continue;
            }
            List<ToolCallRecord> proposals = callsByTurn.getOrDefault(call.getId(), List.of());
            history.add(new LlmMessage.Assistant(call.getAssistantText(), proposals.stream()
                    .map(proposal -> new LlmToolCall(proposal.llmToolCallId(), proposal.toolName(),
                            proposal.arguments()))
                    .toList()));
            for (ToolCallRecord proposal : proposals) {
                history.add(new LlmMessage.ToolResult(proposal.llmToolCallId(), proposal.toolName(),
                        resultContent(proposal)));
            }
        }

        String systemPrompt = SystemPrompt.TEXT + "\nOperational context (provided by the backend):\n"
                + state.contextSnapshot();
        Duration timeout = remainingActive.compareTo(llm.timeout()) < 0 ? remainingActive : llm.timeout();
        return Optional.of(new LlmRequest(systemPrompt, history, specs, llm.maxOutputTokens(), timeout));
    }

    /** What the model learns about one of its proposals: the observed outcome, never more than the record. */
    private String resultContent(ToolCallRecord call) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", call.status().name());
        switch (call.status()) {
            case SUCCEEDED -> {
                result.put("output", call.output() == null ? null : json.readTree(call.output()));
                result.put("outputTruncated", call.outputTruncated());
            }
            case DENIED -> result.put("reason", call.denialReason().name());
            default -> {
                if (call.errorCode() != null) {
                    result.put("errorCode", call.errorCode().name());
                }
                if (call.errorMessage() != null) {
                    result.put("message", call.errorMessage());
                }
            }
        }
        return json.writeValueAsString(result);
    }
}
