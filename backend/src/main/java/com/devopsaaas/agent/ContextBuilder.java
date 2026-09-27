package com.devopsaaas.agent;

import com.devopsaaas.environment.EnvironmentDirectory;
import com.devopsaaas.environment.EnvironmentDirectory.ActiveEnvironment;
import com.devopsaaas.tool.execution.ToolCallRecord;
import com.devopsaaas.tool.execution.ToolExecutionHistory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the operational context of an execution, once, when it is accepted (doc 03, 5.1 "Context
 * Retrieval"). It is stored as {@code agent_execution.context_snapshot}, so what the model saw can be
 * reconstructed exactly later. Only logical service names: container names never reach the model.
 */
@Component
class ContextBuilder {

    static final int RECENT_ACTIONS = 5;
    private static final Duration RECENT_WINDOW = Duration.ofHours(24);
    private static final int RECENT_EXECUTIONS = 20;

    private final EnvironmentDirectory environments;
    private final ToolExecutionHistory tools;
    private final AgentExecutionRepository executions;
    private final JsonMapper json;

    ContextBuilder(EnvironmentDirectory environments, ToolExecutionHistory tools, AgentExecutionRepository executions,
            JsonMapper json) {
        this.environments = environments;
        this.tools = tools;
        this.executions = executions;
        this.json = json;
    }

    String snapshot(ActiveEnvironment environment, int windowFromSeq, int windowToSeq) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("environment", Map.of(
                "name", environment.name(),
                "tier", environment.tier().name(),
                "autonomyLevel", environment.autonomyLevel().name()));
        context.put("services", environments.findEnabledServices(environment.organizationId(), environment.id())
                .stream()
                .map(service -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("service", service.name());
                    view.put("description", service.description());
                    return view;
                })
                .toList());
        context.put("recentActions", recentActions(environment));
        context.put("conversationWindow", Map.of("fromSeq", windowFromSeq, "toSeq", windowToSeq));
        return json.writeValueAsString(context);
    }

    private List<Map<String, Object>> recentActions(ActiveEnvironment environment) {
        List<UUID> recent = executions.recentIdsInEnvironment(environment.organizationId(), environment.id(),
                Instant.now().minus(RECENT_WINDOW), Limit.of(RECENT_EXECUTIONS));
        Map<UUID, String> names = environments.serviceNames(environment.organizationId(), environment.id());
        return tools.forAgentExecutions(environment.organizationId(), recent).stream()
                .limit(RECENT_ACTIONS)
                .map(call -> action(call, names))
                .toList();
    }

    private static Map<String, Object> action(ToolCallRecord call, Map<UUID, String> names) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tool", call.toolName());
        view.put("service", call.targetServiceId() == null ? null : names.get(call.targetServiceId()));
        view.put("status", call.status().name());
        view.put("at", String.valueOf(call.finishedAt() != null ? call.finishedAt() : call.createdAt()));
        return view;
    }
}
