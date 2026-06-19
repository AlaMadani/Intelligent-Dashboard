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

@Component("gemini")
@Slf4j
public class GeminiLlmProvider implements LlmProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_OUTPUT_TOKENS = 4096;

    private final WebClient webClient;

    @Value("${spring.ai.google.genai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.google.genai.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}")
    private String model;

    public GeminiLlmProvider() {
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector())
                .build();
    }

    @Override
    public LlmProviderResponse generate(LlmProviderRequest request) {
        if (!isConfigured()) {
            return LlmProviderResponse.builder()
                    .provider(providerName())
                    .model(model)
                    .success(false)
                    .errorCode("NOT_CONFIGURED")
                    .errorMessage("Gemini API key not configured")
                    .build();
        }

        Instant start = Instant.now();
        try {
            String combinedPrompt = request.getSystemPrompt() + "\n\n" + request.getUserPrompt();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", combinedPrompt)))));
            body.put("generationConfig", Map.of(
                    "temperature", 0.1,
                    "topP", 0.8,
                    "maxOutputTokens", MAX_OUTPUT_TOKENS
            ));

            log.info("LLM_PROVIDER_REQUEST provider=gemini model={} eventId={}", model, request.getEventId());

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

            int promptTokens = response.path("usageMetadata").path("promptTokenCount").asInt(0);
            int completionTokens = response.path("usageMetadata").path("candidatesTokenCount").asInt(0);
            int totalTokens = response.path("usageMetadata").path("totalTokenCount").asInt(0);

            log.info("LLM_PROVIDER_RESPONSE provider=gemini model={} eventId={} latencyMs={} promptTokens={} completionTokens={} status=OK",
                    model, request.getEventId(), ms, promptTokens, completionTokens);

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

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getModel() {
        return model;
    }

    private String extractText(JsonNode response) {
        if (response == null) {
            return null;
        }
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
