package com.devopsaaas.tool.registry;

import com.devopsaaas.tool.api.ApprovalRequirement;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.Tool;
import com.devopsaaas.tool.api.ToolDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Invariants of docs/05-contratos-das-ferramentas.md, section 3.1. A violation turns a tool misconfiguration
 * into a startup error instead of a security problem discovered in production.
 */
final class ToolDefinitionValidator {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-zA-Z0-9]{2,63}$");
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int MIN_OUTPUT_BYTES = 1024;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private ToolDefinitionValidator() {
    }

    static List<String> violations(Tool<?> tool, JsonSchemaGenerator schemas) {
        ToolDefinition definition = tool.definition();
        String name = definition == null ? tool.getClass().getSimpleName() : definition.name();
        List<String> problems = new ArrayList<>();
        if (definition == null) {
            problems.add(name + ": definition is missing");
            return problems;
        }
        if (name == null || !NAME.matcher(name).matches()) {
            problems.add(name + ": name must match " + NAME.pattern());
        }
        if (definition.version() < 1) {
            problems.add(name + ": version must be >= 1");
        }
        if (definition.description() == null || definition.description().isBlank()
                || definition.description().length() > MAX_DESCRIPTION_LENGTH) {
            problems.add(name + ": description must be non-blank and at most " + MAX_DESCRIPTION_LENGTH + " chars");
        }
        if (definition.category() == null || definition.riskLevel() == null
                || definition.requiredPermission() == null || definition.approvalRequirement() == null) {
            problems.add(name + ": category, riskLevel, requiredPermission and approvalRequirement are required");
            return problems;
        }
        if (definition.retryable() && definition.riskLevel() != RiskLevel.READ_ONLY) {
            problems.add(name + ": only READ_ONLY tools may be retryable");
        }
        if (definition.riskLevel() == RiskLevel.DESTRUCTIVE
                && definition.approvalRequirement() != ApprovalRequirement.ALWAYS) {
            problems.add(name + ": DESTRUCTIVE tools must require approval ALWAYS");
        }
        if (definition.riskLevel() != RiskLevel.READ_ONLY
                && (definition.impactDescription() == null || definition.impactDescription().isBlank())) {
            problems.add(name + ": impactDescription is required unless the tool is READ_ONLY");
        }
        if (definition.timeout() == null || definition.timeout().compareTo(MIN_TIMEOUT) < 0
                || definition.timeout().compareTo(MAX_TIMEOUT) > 0) {
            problems.add(name + ": timeout must be between " + MIN_TIMEOUT + " and " + MAX_TIMEOUT);
        }
        if (definition.maxOutputBytes() < MIN_OUTPUT_BYTES || definition.maxOutputBytes() > MAX_OUTPUT_BYTES) {
            problems.add(name + ": maxOutputBytes must be between " + MIN_OUTPUT_BYTES + " and " + MAX_OUTPUT_BYTES);
        }
        if (tool.inputType() == null || !tool.inputType().isRecord()) {
            problems.add(name + ": input type must be a record");
            return problems;
        }
        if (definition.hasTarget() && !hasStringComponent(tool.inputType(), definition.targetParameter())) {
            problems.add(name + ": targetParameter '" + definition.targetParameter()
                    + "' must be a String component of " + tool.inputType().getSimpleName());
        }
        try {
            schemas.schemaFor(tool.inputType());
        } catch (JsonSchemaGenerator.UnsupportedInputException exception) {
            problems.add(name + ": " + exception.getMessage());
        }
        return problems;
    }

    private static boolean hasStringComponent(Class<?> inputType, String componentName) {
        return Arrays.stream(inputType.getRecordComponents())
                .anyMatch(component -> component.getName().equals(componentName)
                        && component.getType() == String.class);
    }
}
