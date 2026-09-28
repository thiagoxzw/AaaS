package com.devopsaaas.tool.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Field-by-field cleaning keeps the JSON valid; the size cap never produces broken JSON. */
class OutputProcessorTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final OutputProcessor processor = new OutputProcessor(json);

    @Test
    void cleansEveryStringField_includingNestedOnes_andCountsRedactions() {
        Map<String, Object> data = Map.of(
                "message", "\u001B[31mtoken=abc123\u001B[0m",
                "nested", Map.of("lines", List.of("ok", "password=hunter2")));

        OutputProcessor.Processed processed = processor.processOutput(data, List.of(), 16 * 1024);

        JsonNode tree = json.readTree(processed.json());
        assertThat(tree.get("data").get("message").asString()).isEqualTo("token=<redacted>");
        assertThat(tree.get("data").get("nested").get("lines").get(1).asString()).isEqualTo("password=<redacted>");
        assertThat(processed.redactions()).isEqualTo(2);
        assertThat(processed.truncated()).isFalse();
    }

    /** Slice 9a, finding 9a-01: which arguments storing would change, and therefore never be approved. */
    @Test
    void altersArguments_isTrueExactlyWhenSanitizingOrMaskingChangesAValue() {
        assertThat(processor.altersArguments("{\"service\":\"demo-api\",\"reason\":\"The pool is exhausted.\"}"))
                .isFalse();
        assertThat(processor.altersArguments("{\"reason\":\"line one\\nline two\\tindented\"}"))
                .as("newlines and tabs are kept").isFalse();
        assertThat(processor.altersArguments("{\"reason\":\"logs show 'password: rejected'\"}")).isTrue();
        assertThat(processor.altersArguments("{\"reason\":\"sent Bearer abcdefgh12345678\"}")).isTrue();
        assertThat(processor.altersArguments("{\"reason\":\"carriage\\rreturn\"}")).isTrue();
        assertThat(processor.altersArguments("{\"reason\":\"zero\\u200Bwidth\"}")).isTrue();
        assertThat(processor.altersArguments("{\"nested\":{\"list\":[\"ok\",\"token=abc123\"]}}")).isTrue();
        assertThat(processor.altersArguments("{\"tail\":20,\"since\":null}")).isFalse();
    }

    @Test
    void oversizedOutput_becomesAValidJsonPreview() {
        OutputProcessor.Processed processed =
                processor.processOutput(Map.of("blob", "é".repeat(5000)), List.of(), 1024);

        JsonNode tree = json.readTree(processed.json());
        assertThat(processed.truncated()).isTrue();
        assertThat(tree.get("truncated").asBoolean()).isTrue();
        assertThat(tree.get("originalBytes").asInt()).isGreaterThan(10_000);
        assertThat(processed.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1024);
    }

    @Test
    void unparsableArguments_areKeptAsABoundedCleanedString() {
        OutputProcessor.Processed processed = processor.processRawArguments("not json password=hunter2 " + "x".repeat(3000));

        String raw = json.readTree(processed.json()).get("_raw").asString();
        assertThat(raw).doesNotContain("hunter2").hasSizeLessThanOrEqualTo(OutputProcessor.MAX_RAW_ARGUMENT_CHARS);
        assertThat(processed.truncated()).isTrue();
    }
}
