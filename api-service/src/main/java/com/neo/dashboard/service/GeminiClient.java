package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class GeminiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_OUTPUT_TOKENS = 2048;

    private final WebClient webClient;

    @Value("${spring.ai.google.genai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.google.genai.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}")
    private String model;

    public GeminiClient() {
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector())
                .build();
    }

    public Optional<String> generate(String eventId, String prompt) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            body.put("generationConfig", Map.of(
                    "temperature", 0.1,
                    "topP", 0.8,
                    "maxOutputTokens", MAX_OUTPUT_TOKENS,
                    "thinkingConfig", Map.of("thinkingBudget", 0)
            ));

            JsonNode response = webClient.post()
                    .uri(baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .onErrorResume(TimeoutException.class, e -> {
                        log.warn("Gemini call timed out for eventId={}", eventId);
                        return Mono.empty();
                    })
                    .onErrorResume(e -> {
                        log.warn("Gemini call failed for eventId={}", eventId, e);
                        return Mono.empty();
                    })
                    .block(TIMEOUT.plusSeconds(1));
            return Optional.ofNullable(extractText(response)).filter(text -> !text.isBlank());
        } catch (Exception e) {
            log.warn("Gemini call failed for eventId={}", eventId, e);
            return Optional.empty();
        }
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
