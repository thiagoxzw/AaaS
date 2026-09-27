package com.devopsaaas.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlmConfigurationTest {

    @Test
    void aProviderThatDoesNotExistYet_stopsTheApplicationAtStartup() {
        LlmProperties openai = new LlmProperties("openai", Duration.ofSeconds(30), 1024, "classpath*:none/*.json");

        assertThatThrownBy(() -> new LlmConfiguration().llmGateway(openai, message -> Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slice 6");
    }
}
