package com.devopsaaas.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devopsaaas.tool.api.Description;
import com.devopsaaas.tool.api.ToolInput;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlatRecordSchemaGeneratorTest {

    enum Stream { STDOUT, STDERR }

    record LogsInput(
            @Description("Service name") @NotBlank @Pattern(regexp = "^[a-z]+$") String service,
            @Min(1) @Max(500) Integer tail,
            @Size(max = 10) String since,
            boolean timestamps,
            Stream stream) implements ToolInput {
    }

    record NestedInput(Map<String, String> labels) implements ToolInput {
    }

    record ListInput(List<String> services) implements ToolInput {
    }

    private final FlatRecordSchemaGenerator generator = new FlatRecordSchemaGenerator();

    @Test
    @SuppressWarnings("unchecked")
    void describesTypesConstraintsAndRequiredFields_andForbidsExtraProperties() {
        Map<String, Object> schema = generator.schemaFor(LogsInput.class);
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schema.get("properties");

        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrder("service", "timestamps");
        assertThat(properties.get("service")).containsEntry("type", "string")
                .containsEntry("pattern", "^[a-z]+$").containsEntry("minLength", 1)
                .containsEntry("description", "Service name");
        assertThat(properties.get("tail")).containsEntry("type", "integer")
                .containsEntry("minimum", 1L).containsEntry("maximum", 500L);
        assertThat(properties.get("since")).containsEntry("maxLength", 10);
        assertThat(properties.get("timestamps")).containsEntry("type", "boolean");
        assertThat(properties.get("stream")).containsEntry("type", "string")
                .containsEntry("enum", List.of("STDOUT", "STDERR"));
    }

    @Test
    void rejectsMapsAndLists_soTheLlmCannotPassArbitraryStructures() {
        assertThatThrownBy(() -> generator.schemaFor(NestedInput.class))
                .isInstanceOf(JsonSchemaGenerator.UnsupportedInputException.class)
                .hasMessageContaining("labels");
        assertThatThrownBy(() -> generator.schemaFor(ListInput.class))
                .isInstanceOf(JsonSchemaGenerator.UnsupportedInputException.class)
                .hasMessageContaining("services");
    }
}
