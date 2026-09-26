package com.devopsaaas.tool.registry;

import com.devopsaaas.tool.api.Description;
import com.devopsaaas.tool.api.ToolInput;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * JSON Schema for flat input records: strings, integers, numbers, booleans and enums, with the Bean Validation
 * constraints the executor also enforces (single source of truth). Nested objects, lists and maps are rejected
 * on purpose, so the LLM can never pass arbitrary structures to a tool.
 */
@Component
public class FlatRecordSchemaGenerator implements JsonSchemaGenerator {

    private static final Set<Class<?>> INTEGERS = Set.of(int.class, Integer.class, long.class, Long.class,
            short.class, Short.class);
    private static final Set<Class<?>> NUMBERS = Set.of(double.class, Double.class, float.class, Float.class,
            BigDecimal.class);

    @Override
    public Map<String, Object> schemaFor(Class<? extends ToolInput> inputType) {
        if (!inputType.isRecord()) {
            throw new UnsupportedInputException(inputType.getSimpleName() + " must be a record");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : inputType.getRecordComponents()) {
            properties.put(component.getName(), propertySchema(inputType, component));
            if (isRequired(component)) {
                required.add(component.getName());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> propertySchema(Class<?> owner, RecordComponent component) {
        Class<?> type = component.getType();
        Map<String, Object> property = new LinkedHashMap<>();
        if (type == String.class) {
            property.put("type", "string");
        } else if (INTEGERS.contains(type)) {
            property.put("type", "integer");
        } else if (NUMBERS.contains(type)) {
            property.put("type", "number");
        } else if (type == boolean.class || type == Boolean.class) {
            property.put("type", "boolean");
        } else if (type.isEnum()) {
            property.put("type", "string");
            property.put("enum", Arrays.stream(type.getEnumConstants()).map(Object::toString).toList());
        } else {
            throw new UnsupportedInputException(owner.getSimpleName() + "." + component.getName()
                    + " has unsupported type " + type.getSimpleName()
                    + " (tool inputs are flat: string, integer, number, boolean or enum)");
        }

        Description description = find(component, Description.class);
        if (description != null) {
            property.put("description", description.value());
        }
        Pattern pattern = find(component, Pattern.class);
        if (pattern != null) {
            property.put("pattern", pattern.regexp());
        }
        Size size = find(component, Size.class);
        if (size != null) {
            property.put("minLength", size.min());
            if (size.max() != Integer.MAX_VALUE) {
                property.put("maxLength", size.max());
            }
        } else if (find(component, NotBlank.class) != null || find(component, NotEmpty.class) != null) {
            property.put("minLength", 1);
        }
        Min min = find(component, Min.class);
        if (min != null) {
            property.put("minimum", min.value());
        }
        Max max = find(component, Max.class);
        if (max != null) {
            property.put("maximum", max.value());
        }
        return property;
    }

    private static boolean isRequired(RecordComponent component) {
        return component.getType().isPrimitive()
                || find(component, NotNull.class) != null
                || find(component, NotBlank.class) != null
                || find(component, NotEmpty.class) != null;
    }

    /** Constraint annotations on a record component propagate to its accessor; ours are on the component. */
    private static <A extends Annotation> A find(RecordComponent component, Class<A> annotation) {
        A onComponent = component.getAnnotation(annotation);
        return onComponent != null ? onComponent : component.getAccessor().getAnnotation(annotation);
    }
}
