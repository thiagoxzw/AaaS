package com.devopsaaas.llm.scripted;

import com.devopsaaas.llm.LlmException;
import com.devopsaaas.llm.LlmFinishReason;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scripts from JSON files, one script per file, loaded once at startup. A malformed file stops the
 * application. The first script, by priority and then name, whose {@code when} regex is found in the user
 * message answers it.
 */
public final class ClasspathScriptRepository implements ScriptRepository {

    private record TurnFile(String text, List<CallFile> toolCalls, LlmFinishReason finishReason,
            LlmException.Category error) {
    }

    private record CallFile(String name, JsonNode arguments, String argumentsRaw) {
    }

    private record ScriptFile(String name, String when, Integer priority, Boolean repeatLastTurn,
            List<TurnFile> turns) {
    }

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final List<LlmScript> scripts;

    public ClasspathScriptRepository(ResourcePatternResolver resources, String location) {
        List<LlmScript> loaded = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try {
            for (Resource resource : resources.getResources(location)) {
                LlmScript script = parse(resource);
                if (!names.add(script.name())) {
                    throw new IllegalStateException("Duplicate LLM script name: " + script.name());
                }
                loaded.add(script);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot list LLM scripts at " + location, exception);
        }
        loaded.sort(Comparator.comparingInt(LlmScript::priority).reversed().thenComparing(LlmScript::name));
        this.scripts = List.copyOf(loaded);
    }

    public List<LlmScript> scripts() {
        return scripts;
    }

    @Override
    public Optional<LlmScript> find(String userMessage) {
        return scripts.stream().filter(script -> script.when().matcher(userMessage).find()).findFirst();
    }

    private LlmScript parse(Resource resource) {
        ScriptFile file;
        try (InputStream in = resource.getInputStream()) {
            file = mapper.readValue(in, ScriptFile.class);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Invalid LLM script " + resource.getFilename(), exception);
        }
        if (file.name() == null || file.name().isBlank() || file.when() == null || file.turns() == null) {
            throw new IllegalStateException("LLM script " + resource.getFilename() + " needs name, when and turns");
        }
        Pattern when;
        try {
            when = Pattern.compile(file.when());
        } catch (PatternSyntaxException exception) {
            throw new IllegalStateException("LLM script " + file.name() + " has an invalid 'when' regex", exception);
        }
        List<LlmScript.Turn> turns = file.turns().stream().map(this::turn).toList();
        return new LlmScript(file.name(), when, file.priority() == null ? 0 : file.priority(),
                Boolean.TRUE.equals(file.repeatLastTurn()), turns);
    }

    private LlmScript.Turn turn(TurnFile file) {
        List<LlmScript.ToolCall> calls = file.toolCalls() == null ? List.of() : file.toolCalls().stream()
                .map(call -> new LlmScript.ToolCall(call.name(), call.argumentsRaw() != null
                        ? call.argumentsRaw()
                        : call.arguments() == null ? "{}" : mapper.writeValueAsString(call.arguments())))
                .toList();
        return new LlmScript.Turn(file.text(), calls, file.finishReason(), file.error());
    }
}
