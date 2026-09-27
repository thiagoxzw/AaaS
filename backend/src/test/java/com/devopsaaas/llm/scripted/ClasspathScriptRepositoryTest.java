package com.devopsaaas.llm.scripted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ClasspathScriptRepositoryTest {

    private final PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();

    @Test
    void theShippedScripts_load_andAnswerTheDemoQuestion() {
        ClasspathScriptRepository repository = new ClasspathScriptRepository(resources, "classpath*:llm-scripts/*.json");

        assertThat(repository.scripts()).extracting(LlmScript::name).contains("demo-status", "demo-logs");
        assertThat(repository.find("o demo-api está de pé?")).map(LlmScript::name).contains("demo-status");
        assertThat(repository.find("show me the logs")).map(LlmScript::name).contains("demo-logs");
    }

    @Test
    void aMalformedScript_stopsTheApplication(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("bad.json"), "{\"name\":\"bad\",\"when\":\"x\",\"turns\":[],\"extra\":1}");

        assertThatThrownBy(() -> repository(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad.json");
    }

    @Test
    void anInvalidRegex_orADuplicateName_stopsTheApplication(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("regex.json"), "{\"name\":\"r\",\"when\":\"(\",\"turns\":[{\"text\":\"x\"}]}");
        assertThatThrownBy(() -> repository(directory)).hasMessageContaining("invalid 'when' regex");

        Files.writeString(directory.resolve("regex.json"), "{\"name\":\"a\",\"when\":\"x\",\"turns\":[{\"text\":\"x\"}]}");
        Files.writeString(directory.resolve("copy.json"), "{\"name\":\"a\",\"when\":\"y\",\"turns\":[{\"text\":\"y\"}]}");
        assertThatThrownBy(() -> repository(directory)).hasMessageContaining("Duplicate LLM script name");
    }

    @Test
    void priority_decidesBetweenScriptsThatMatch(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("low.json"),
                "{\"name\":\"low\",\"when\":\"status\",\"turns\":[{\"text\":\"low\"}]}");
        Files.writeString(directory.resolve("high.json"),
                "{\"name\":\"high\",\"when\":\"status\",\"priority\":5,\"turns\":[{\"text\":\"high\"}]}");

        assertThat(repository(directory).find("status?")).map(LlmScript::name).contains("high");
    }

    private ClasspathScriptRepository repository(Path directory) {
        return new ClasspathScriptRepository(resources, directory.toUri() + "*.json");
    }
}
