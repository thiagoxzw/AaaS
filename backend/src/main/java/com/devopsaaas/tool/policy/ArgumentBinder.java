package com.devopsaaas.tool.policy;

import com.devopsaaas.tool.api.ToolInput;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the LLM's raw JSON arguments into the tool's typed input: strict parsing (unknown fields, trailing
 * tokens and scalar coercion are rejected) followed by Bean Validation, the same constraints that describe
 * the schema sent to the LLM.
 */
@Component
class ArgumentBinder {

    static final int MAX_ARGUMENT_BYTES = 8 * 1024;

    private final JsonMapper strict = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    private final JsonMapper canonical = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();
    private final Validator validator;

    ArgumentBinder(Validator validator) {
        this.validator = validator;
    }

    sealed interface Binding {
    }

    record Bound(ToolInput input, String canonicalJson, String hash) implements Binding {
    }

    /** {@code problem} is safe to store and to show to the LLM: field names and constraint messages only. */
    record Rejected(String problem) implements Binding {
    }

    Binding bind(String argumentsJson, Class<? extends ToolInput> inputType) {
        String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
            return new Rejected("Arguments exceed " + MAX_ARGUMENT_BYTES + " bytes.");
        }
        ToolInput input;
        try {
            input = strict.readValue(json, inputType);
        } catch (JacksonException exception) {
            return new Rejected("Arguments are not valid JSON for this tool's schema.");
        }
        if (input == null) {
            return new Rejected("Arguments must be a JSON object.");
        }
        Set<ConstraintViolation<ToolInput>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            return new Rejected(violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
        String canonicalJson = canonical.writeValueAsString(input);
        return new Bound(input, canonicalJson, sha256(canonicalJson));
    }

    /** Reads a string component of the input record, used to find the target service. */
    static String componentValue(ToolInput input, String componentName) {
        return Arrays.stream(input.getClass().getRecordComponents())
                .filter(component -> component.getName().equals(componentName))
                .findFirst()
                .map(component -> {
                    try {
                        return (String) component.getAccessor().invoke(input);
                    } catch (IllegalAccessException | InvocationTargetException exception) {
                        throw new IllegalStateException("Cannot read " + componentName, exception);
                    }
                })
                .orElse(null);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
