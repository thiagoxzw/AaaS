package com.devopsaaas.tool.registry;

import com.devopsaaas.tool.api.Tool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The complete, closed catalog of tools known to the system, and the source of truth for their definitions.
 * It never filters by user or environment: deciding what a caller may see or run is the policy's job.
 *
 * <p>Final on purpose: the constructor rejects invalid definitions by throwing, and a final class cannot be
 * subclassed to capture a partially initialized instance (SpotBugs CT_CONSTRUCTOR_THROW).
 */
@Component
public final class ToolRegistry {

    private final Map<String, RegisteredTool> tools;

    @Autowired
    ToolRegistry(ObjectProvider<Tool<?>> tools, JsonSchemaGenerator schemas) {
        this(tools.orderedStream().toList(), schemas);
    }

    public ToolRegistry(Collection<? extends Tool<?>> tools, JsonSchemaGenerator schemas) {
        List<String> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, RegisteredTool> registered = new LinkedHashMap<>();
        for (Tool<?> tool : tools) {
            List<String> violations = ToolDefinitionValidator.violations(tool, schemas);
            problems.addAll(violations);
            if (!violations.isEmpty()) {
                continue;
            }
            String name = tool.definition().name();
            if (!names.add(name)) {
                problems.add(name + ": duplicate tool name");
                continue;
            }
            registered.put(name, new RegisteredTool(tool, tool.definition(), schemas.schemaFor(tool.inputType())));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid tool definitions: " + String.join("; ", problems));
        }
        this.tools = Map.copyOf(registered);
    }

    public Optional<RegisteredTool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name));
    }

    public List<RegisteredTool> all() {
        return tools.values().stream()
                .sorted((left, right) -> left.name().compareTo(right.name()))
                .toList();
    }
}
