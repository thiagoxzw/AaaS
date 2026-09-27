package com.devopsaaas.integration.openai;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OpenAI settings, bound only when {@code devops.llm.provider=openai}. The model and the prices have no default
 * on purpose: model names and prices change, and the application refuses to start rather than guess them.
 *
 * @param apiKey from {@code OPENAI_API_KEY}; never logged ({@link #toString()} hides it)
 * @param inputPricePerMillionUsd from the provider's price page, for the cost estimate (RNF-CUS-01)
 * @param maxRetries extra attempts on 429 and 5xx, within the call's timeout
 * @param maxRetryAfter cap on how long a Retry-After header can make a retry wait
 */
@ConfigurationProperties("devops.llm.openai")
public record OpenAiProperties(
        String apiKey,
        String model,
        @DefaultValue("https://api.openai.com/v1") URI baseUrl,
        BigDecimal inputPricePerMillionUsd,
        BigDecimal outputPricePerMillionUsd,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("10s") Duration maxRetryAfter,
        @DefaultValue("5s") Duration connectTimeout) {

    public OpenAiProperties {
        require(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY (devops.llm.openai.api-key)");
        require(model != null && !model.isBlank(), "LLM_MODEL (devops.llm.openai.model)");
        require(inputPricePerMillionUsd != null && inputPricePerMillionUsd.signum() >= 0,
                "LLM_PRICE_INPUT_PER_MTOK_USD (devops.llm.openai.input-price-per-million-usd)");
        require(outputPricePerMillionUsd != null && outputPricePerMillionUsd.signum() >= 0,
                "LLM_PRICE_OUTPUT_PER_MTOK_USD (devops.llm.openai.output-price-per-million-usd)");
        require(baseUrl != null && ("https".equals(baseUrl.getScheme()) || isLoopback(baseUrl)),
                "an https devops.llm.openai.base-url (plain http only for a local stub)");
        require(maxRetries >= 0, "a non-negative devops.llm.openai.max-retries");
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("LLM_PROVIDER=openai requires " + what);
        }
    }

    private static boolean isLoopback(URI url) {
        return "http".equals(url.getScheme())
                && ("localhost".equals(url.getHost()) || "127.0.0.1".equals(url.getHost()));
    }

    @Override
    public String toString() {
        return "OpenAiProperties[apiKey=<redacted>, model=" + model + ", baseUrl=" + baseUrl + "]";
    }
}
