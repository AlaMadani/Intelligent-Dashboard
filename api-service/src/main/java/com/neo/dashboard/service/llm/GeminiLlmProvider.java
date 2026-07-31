package com.neo.dashboard.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * LLM provider implementation for Google Gemini (Generative Language API).
 * Constructs the request payload, sends it via reactive {@link WebClient},
 * and parses the structured JSON response into an {@link LlmProviderResponse}.
 * <p>
 * Registered as Spring bean named {@code "gemini"} so the factory can
 * select it by name from configuration.
 */
@Component("gemini")
@Slf4j
public class GeminiLlmProvider implements LlmProvider {

    /** Maximum time to wait for a Gemini API response before failing. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Hard limit on the number of tokens Gemini may generate in a single turn. */
    private static final int MAX_OUTPUT_TOKENS = 4096;

    /** Shared reactive HTTP client — created without a base URL because the full URI is built per-request. */
    private final WebClient webClient;

    /** Gemini API key injected from {@code spring.ai.google.genai.api-key}. */
    @Value("${spring.ai.google.genai.api-key:}")
    private String apiKey;

    /** Base URL for the Gemini API — defaults to the public Google endpoint. */
    @Value("${spring.ai.google.genai.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    /** Model identifier (e.g. {@code gemini-2.5-flash}) from configuration. */
    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}")
    private String model;

    public GeminiLlmProvider() {
        /* Build a vanilla WebClient with no default URI — the URI is assembled inline with the model path and API key. */
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector())
                .build();
    }

    @Override
    public LlmProviderResponse generate(LlmProviderRequest request) {
        /* Short-circuit if the API key is absent so callers get a clear "not configured" signal. */
        if (!isConfigured()) {
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("NOT_CONFIGURED")
                    .errorMessage("Gemini API key not configured")
                    .build();
        }

        /* Record the start time for latency measurement. */
        Instant start = Instant.now();
        try {
            /* Concatenate system and user prompts into a single text block — Gemini's /generateContent endpoint expects everything in one parts list. */
            String combinedPrompt = request.getSystemPrompt() + "\n\n" + request.getUserPrompt();

            /* Build the request body with the contents array and generation-config overrides. */
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", combinedPrompt)))));
            body.put("generationConfig", Map.of(
                    "temperature", 0.1,
                    "topP", 0.8,
                    "maxOutputTokens", MAX_OUTPUT_TOKENS
            ));

            log.info("LLM_PROVIDER_REQUEST provider=gemini model={} eventId={}", model, request.getEventId());

            /* POST to the Gemini generateContent endpoint and block for the response. Timeout and request errors are swallowed, returning an empty Mono. */
            JsonNode response = webClient.post()
                    .uri(baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .onErrorResume(TimeoutException.class, e -> {
                        log.warn("LLM_PROVIDER_ERROR provider=gemini model={} status=TIMEOUT eventId={}", model, request.getEventId());
                        return Mono.empty();
                    })
                    .onErrorResume(e -> {
                        log.warn("LLM_PROVIDER_ERROR provider=gemini model={} status=REQUEST_ERROR eventId={} errorClass={}",
                                model, request.getEventId(), e.getClass().getSimpleName(), e);
                        return Mono.empty();
                    })
                    .block(TIMEOUT.plusSeconds(5));

            /* If the API returned nothing (timeout or error swallowed above), report EMPTY_RESPONSE. */
            if (response == null) {
                long ms = Duration.between(start, Instant.now()).toMillis();
                return LlmProviderResponse.builder()
                        .provider(providerName())
                        .model(model)
                        .success(false)
                        .errorCode("EMPTY_RESPONSE")
                        .errorMessage("Gemini returned empty response")
                        .latencyMs(ms)
                        .build();
            }

            long ms = Duration.between(start, Instant.now()).toMillis();
            String text = extractText(response);

            /* Even if a JSON body came back, it may contain no candidate content — treat that as an error. */
            if (text == null || text.isBlank()) {
                return LlmProviderResponse.builder()
                        .provider(providerName())
                        .model(model)
                        .success(false)
                        .errorCode("EMPTY_CONTENT")
                        .errorMessage("Gemini returned empty content")
                        .latencyMs(ms)
                        .build();
            }

            /* Read token-usage metadata from the response structure; default to 0 if absent. */
            int promptTokens = response.path("usageMetadata").path("promptTokenCount").asInt(0);
            int completionTokens = response.path("usageMetadata").path("candidatesTokenCount").asInt(0);
            int totalTokens = response.path("usageMetadata").path("totalTokenCount").asInt(0);

            log.info("LLM_PROVIDER_RESPONSE provider=gemini model={} eventId={} latencyMs={} promptTokens={} completionTokens={} status=OK",
                    model, request.getEventId(), ms, promptTokens, completionTokens);

            /* Assemble a successful response; token counts are set to null when the API did not report them (value ≤ 0). */
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .text(text.trim())
                    .success(true)
                    .promptTokens(promptTokens > 0 ? promptTokens : null)
                    .completionTokens(completionTokens > 0 ? completionTokens : null)
                    .totalTokens(totalTokens > 0 ? totalTokens : null)
                    .latencyMs(ms)
                    .build();

        } catch (Exception e) {
            /* Catch-all for unexpected errors (JSON parse, network, etc.). */
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM_PROVIDER_ERROR provider=gemini model={} status=EXCEPTION eventId={} errorClass={}",
                    model, request.getEventId(), e.getClass().getSimpleName(), e);
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("EXCEPTION")
                    .errorMessage(e.getMessage())
                    .latencyMs(ms)
                    .build();
        }
    }

    @Override
    public String providerName() {
        return "gemini";
    }

    /** Returns {@code true} when the API key has been provided and is non-blank. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Returns the model identifier currently configured for this provider. */
    public String getModel() {
        return model;
    }

    /**
     * Navigates the Gemini JSON response tree to extract the concatenated text
     * from all candidate parts. Returns {@code null} when no text is found.
     */
    private String extractText(JsonNode response) {
        if (response == null) {
            return null;
        }
        /* The response structure is: /candidates/0/content/parts[].text */
        JsonNode parts = response.at("/candidates/0/content/parts");
        if (!parts.isArray()) {
            return null;
        }
        List<String> chunks = new ArrayList<>();
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                chunks.add(text.trim());
            }
        }
        return chunks.isEmpty() ? null : String.join("\n\n", chunks);
    }
}
