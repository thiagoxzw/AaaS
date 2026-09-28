package com.devopsaaas.tool.execution;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Prepares anything a tool produced (or an LLM proposed) for storage and for the LLM: every string value is
 * sanitized and then redacted, field by field, so the JSON is never corrupted; only then the size cap is
 * applied. Nothing reaches the database before passing through here.
 */
@Component
class OutputProcessor {

    static final int MAX_RAW_ARGUMENT_CHARS = 2000;
    private static final int PREVIEW_MARGIN_BYTES = 256;

    private final JsonMapper jsonMapper;

    OutputProcessor(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    record Processed(String json, boolean truncated, int redactions) {
    }

    /** Tool output: {@code {"data": ..., "findings": [...]}} after sanitization, redaction and size cap. */
    Processed processOutput(Object data, Object findings, int maxBytes) {
        ObjectNode payload = jsonMapper.createObjectNode();
        payload.set("data", jsonMapper.valueToTree(data));
        payload.set("findings", jsonMapper.valueToTree(findings));
        int redactions = clean(payload);
        String json = jsonMapper.writeValueAsString(payload);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes <= maxBytes) {
            return new Processed(json, false, redactions);
        }
        ObjectNode preview = jsonMapper.createObjectNode();
        preview.put("truncated", true);
        preview.put("originalBytes", bytes);
        preview.put("preview", truncateToBytes(json, Math.max(0, maxBytes - PREVIEW_MARGIN_BYTES)));
        return new Processed(jsonMapper.writeValueAsString(preview), true, redactions);
    }

    /** Validated, canonical arguments of an allowed or policy-denied call. */
    Processed processArguments(String canonicalJson) {
        JsonNode tree = jsonMapper.readTree(canonicalJson);
        int redactions = clean(tree);
        return new Processed(jsonMapper.writeValueAsString(tree), false, redactions);
    }

    /**
     * Slice 9a (finding 9a-01): whether storing these arguments would change them. The hash a human approves
     * covers the arguments as proposed, while the stored copy is sanitized and masked; if the two differ, the
     * approved call could never run as shown.
     */
    boolean altersArguments(String canonicalJson) {
        JsonNode original = jsonMapper.readTree(canonicalJson);
        JsonNode cleaned = original.deepCopy();
        clean(cleaned);
        return !original.equals(cleaned);
    }

    /** Arguments that could not be parsed: kept as a bounded, cleaned string for the audit trail. */
    Processed processRawArguments(String raw) {
        String bounded = raw == null ? "" : raw.length() > MAX_RAW_ARGUMENT_CHARS
                ? raw.substring(0, MAX_RAW_ARGUMENT_CHARS) : raw;
        SecretRedactor.Result cleaned = SecretRedactor.redact(OutputSanitizer.sanitize(bounded));
        // Masking can make the text longer ("<redacted>"), so the bound is applied again after cleaning.
        String text = cleaned.text().length() > MAX_RAW_ARGUMENT_CHARS
                ? cleaned.text().substring(0, MAX_RAW_ARGUMENT_CHARS) : cleaned.text();
        String json = jsonMapper.writeValueAsString(Map.of("_raw", text));
        return new Processed(json, raw != null && raw.length() > MAX_RAW_ARGUMENT_CHARS, cleaned.redactions());
    }

    /** Free text (error messages, names, rationales): sanitized, redacted and bounded. */
    static String cleanText(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String cleaned = SecretRedactor.redact(OutputSanitizer.sanitize(value)).text();
        return cleaned.length() > maxChars ? cleaned.substring(0, maxChars) : cleaned;
    }

    private static int clean(JsonNode node) {
        int redactions = 0;
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(object.propertyNames());
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child.isString()) {
                    SecretRedactor.Result result = SecretRedactor.redact(OutputSanitizer.sanitize(child.asString()));
                    object.set(name, StringNode.valueOf(result.text()));
                    redactions += result.redactions();
                } else {
                    redactions += clean(child);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child.isString()) {
                    SecretRedactor.Result result = SecretRedactor.redact(OutputSanitizer.sanitize(child.asString()));
                    array.set(index, StringNode.valueOf(result.text()));
                    redactions += result.redactions();
                } else {
                    redactions += clean(child);
                }
            }
        }
        return redactions;
    }

    private static String truncateToBytes(String value, int maxBytes) {
        StringBuilder builder = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            builder.appendCodePoint(codePoint);
            bytes += size;
            offset += Character.charCount(codePoint);
        }
        return builder.toString();
    }
}
