package com.neo.dashboard.service;

import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.dto.AnomalyExplanationDto;
import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.dto.SessionAnalysisDto;
import com.neo.dashboard.dto.StatsResponseDto;
import com.neo.dashboard.dto.UserRiskProfileDto;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnomalyExplanationService {

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final String CACHE_PREFIX = "ai:explanation:anomaly:";
    private static final String RAW_RESPONSE_CACHE_PREFIX = "ai:explanation:anomaly:raw:";
    private static final int MAX_OUTPUT_TOKENS = 2048;
    private static final int THINKING_BUDGET = 0;
    private static final int MAX_JSON_CHARS = 400;
    private static final int MAX_STATS_ITEMS = 3;
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "assessment",
            "evidence",
            "operational impact",
            "recommended action"
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AnomalyEventRepository anomalyEventRepository;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final SessionAnalysisService sessionAnalysisService;
    private final RiskProfileService riskProfileService;
    private final NextActionPredictionService nextActionPredictionService;
    private final ActiveAnomalyService activeAnomalyService;
    private final StatsService statsService;

    @Value("${spring.ai.google.genai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}")
    private String model;

    @Transactional(readOnly = true)
    public Optional<AnomalyExplanationDto> explain(Long anomalyEventId, boolean refresh) {
        if (!refresh) {
            AnomalyExplanationDto cached = getCached(anomalyEventId);
            if (cached != null) {
                cached.setCached(true);
                return Optional.of(cached);
            }
        }

        Optional<AnomalyEvent> anomalyOpt = anomalyEventRepository.findById(anomalyEventId);
        if (anomalyOpt.isEmpty()) {
            return Optional.empty();
        }

        AnomalyEvent anomaly = anomalyOpt.get();
        SessionAnalysisDto session = sessionAnalysisRepository
                .findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anomaly.getInsuredId(), anomaly.getSessionId())
                .map(sessionAnalysisService::toDto)
                .orElse(null);
        UserRiskProfileDto risk = riskProfileService.getRiskProfile(anomaly.getInsuredId()).orElse(null);
        NextActionPredictionDto nextActions = nextActionPredictionService.getPrediction(anomaly.getInsuredId()).orElse(null);
        AnomalyAlertDto activeAnomaly = activeAnomalyService.getActiveAnomaly(anomaly.getInsuredId()).orElse(null);
        StatsResponseDto liveStats = statsService.getLiveStats(LocalDate.now());
        StatsResponseDto trendStats = statsService.getTrendStats(LocalDate.now().plusDays(1));

        String prompt = buildPrompt(anomaly, session, risk, nextActions, activeAnomaly, liveStats, trendStats);
        String aiText = generateWithGemini(anomalyEventId, prompt);
        String source = aiText == null || aiText.isBlank() ? "heuristic" : "gemini";
        String explanation = (aiText == null || aiText.isBlank())
                ? buildFallbackExplanation(anomaly, session, risk, nextActions, activeAnomaly)
                : aiText.trim();

        AnomalyExplanationDto dto = new AnomalyExplanationDto(
                anomalyEventId,
                source,
                "heuristic".equals(source) ? "local-fallback" : model,
                Instant.now(),
                false,
                explanation
        );
        cache(dto);
        return Optional.of(dto);
    }

    private AnomalyExplanationDto getCached(Long anomalyEventId) {
        String payload = redisTemplate.opsForValue().get(CACHE_PREFIX + anomalyEventId);
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            AnomalyExplanationDto dto = objectMapper.readValue(payload, AnomalyExplanationDto.class);
            if (dto == null || !hasRequiredSections(dto.getExplanation())) {
                log.info("Ignoring cached anomaly explanation {} because it is incomplete", anomalyEventId);
                return null;
            }
            return dto;
        } catch (Exception e) {
            log.warn("Failed to read cached anomaly explanation {}", anomalyEventId, e);
            return null;
        }
    }

    private void cache(AnomalyExplanationDto dto) {
        try {
            redisTemplate.opsForValue().set(
                    CACHE_PREFIX + dto.getAnomalyEventId(),
                    objectMapper.writeValueAsString(dto),
                    CACHE_TTL
            );
        } catch (Exception e) {
            log.warn("Failed to cache anomaly explanation {}", dto.getAnomalyEventId(), e);
        }
    }

    private String generateWithGemini(Long anomalyEventId, String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        try {
            RestClient client = RestClient.builder()
                    .baseUrl("https://generativelanguage.googleapis.com")
                    .build();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", List.of(Map.of(
                    "parts", List.of(Map.of("text", prompt))
            )));
            body.put("generationConfig", Map.of(
                    "temperature", 0.1,
                    "topP", 0.8,
                    "maxOutputTokens", MAX_OUTPUT_TOKENS,
                    "thinkingConfig", Map.of(
                            "thinkingBudget", THINKING_BUDGET
                    )
            ));

            String rawResponse = client.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1beta/models/{model}:generateContent")
                            .queryParam("key", apiKey)
                            .build(model))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (rawResponse == null || rawResponse.isBlank()) {
                log.warn("Gemini explanation response was empty for anomaly {}", anomalyEventId);
                return null;
            }
            storeRawGeminiResponse(anomalyEventId, rawResponse);

            JsonNode response;
            try {
                response = objectMapper.readTree(rawResponse);
            } catch (Exception e) {
                log.warn("Failed to parse raw Gemini explanation response for anomaly {}. Raw response: {}", anomalyEventId, rawResponse, e);
                return null;
            }

            String content = extractGeminiText(response);
            String finishReason = response.at("/candidates/0/finishReason").asText("UNKNOWN");
            if (content == null || content.isBlank()) {
                log.warn("Gemini explanation response did not contain text parts for anomaly {}. finishReason={}. Raw response: {}", anomalyEventId, finishReason, rawResponse);
                return null;
            }
            if (!hasRequiredSections(content)) {
                log.warn("Gemini explanation response was incomplete for anomaly {}. finishReason={}. Raw response: {}", anomalyEventId, finishReason, rawResponse);
                return null;
            }
            return content;
        } catch (Exception e) {
            log.warn("Gemini explanation call failed, using fallback explanation", e);
            return null;
        }
    }

    private String buildPrompt(AnomalyEvent anomaly,
                               SessionAnalysisDto session,
                               UserRiskProfileDto risk,
                               NextActionPredictionDto nextActions,
                               AnomalyAlertDto activeAnomaly,
                               StatsResponseDto liveStats,
                               StatsResponseDto trendStats) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an insurance fraud and behavioral anomaly analyst.\n")
                .append("Write a short operational explanation for the anomaly below.\n")
                .append("Output markdown with exactly these sections and nothing else:\n")
                .append("## Assessment\n## Evidence\n## Operational impact\n## Recommended action\n")
                .append("Keep it concise: 1-2 bullets per section, plain factual wording, no filler.\n")
                .append("Use only the context provided. If data is missing, say n/a briefly.\n\n")
                .append("Anomaly summary:\n")
                .append("- Insured ID: ").append(orUnknown(anomaly.getInsuredId())).append('\n')
                .append("- Session ID: ").append(orUnknown(anomaly.getSessionId())).append('\n')
                .append("- Event ID: ").append(orUnknown(anomaly.getEventId())).append('\n')
                .append("- Tier: ").append(orUnknown(anomaly.getAnomalyTier())).append('\n')
                .append("- Type: ").append(orUnknown(anomaly.getAnomalyType())).append('\n')
                .append("- Score: ").append(orUnknown(anomaly.getAnomalyScore())).append('\n')
                .append("- Confidence: ").append(orUnknown(anomaly.getTypeConfidence())).append('\n')
                .append("- Triggered rules: ").append(orUnknown(anomaly.getRuleType())).append('\n')
                .append("- Event time: ").append(orUnknown(anomaly.getEventTime())).append('\n')
                .append("- Detected at: ").append(orUnknown(anomaly.getDetectedAt())).append('\n')
                .append("- Raw payload summary: ").append(sanitizeJson(anomaly.getEventJson())).append("\n\n");

        prompt.append("Session summary:\n")
                .append("- Available: ").append(session != null).append('\n');
        if (session != null) {
            prompt.append("- Session length: ").append(orUnknown(session.getSessionLength())).append('\n')
                    .append("- Duration seconds: ").append(orUnknown(session.getSessionDurationSeconds())).append('\n')
                    .append("- Unique actions: ").append(orUnknown(session.getUniqueActionCount())).append('\n')
                    .append("- KO rate: ").append(orUnknown(session.getKoRate())).append('\n')
                    .append("- AE score: ").append(orUnknown(session.getAeScore())).append('\n')
                    .append("- Session anomaly flag: ").append(orUnknown(session.getIsAnomaly())).append('\n')
                    .append("- Session anomaly type: ").append(orUnknown(session.getAnomalyType())).append('\n')
                    .append("- Rule type: ").append(orUnknown(session.getRuleType())).append('\n')
                    .append("- Top next actions: ").append(session.getTop3NextActions()).append('\n');
        }

        prompt.append("\nRisk summary:\n")
                .append("- Available: ").append(risk != null).append('\n');
        if (risk != null) {
            prompt.append("- Risk tier: ").append(orUnknown(risk.getRiskTier())).append('\n')
                    .append("- 30d anomaly rate: ").append(orUnknown(risk.getAnomalyRate30d())).append('\n')
                    .append("- 30d sessions: ").append(orUnknown(risk.getSessions30d())).append('\n')
                    .append("- Last anomaly type: ").append(orUnknown(risk.getLastAnomalyType())).append('\n')
                    .append("- Consecutive clean sessions: ").append(orUnknown(risk.getConsecutiveCleanSessions())).append('\n');
        }

        prompt.append("\nPredicted next actions:\n")
                .append(nextActions == null ? "- Not available\n" : "- Top-3: " + nextActions.getTop3Actions() + "\n");

        prompt.append("\nCurrent active anomaly:\n")
                .append(activeAnomaly == null ? "- None\n" : "- Tier=" + orUnknown(activeAnomaly.getAnomalyTier())
                        + ", Type=" + orUnknown(activeAnomaly.getAnomalyType())
                        + ", Score=" + orUnknown(activeAnomaly.getAnomalyScore()) + "\n");

        prompt.append("\nPlatform stats summary:\n")
                .append("- Live: ").append(summarizeStats(liveStats)).append('\n')
                .append("- Trend: ").append(summarizeStats(trendStats));

        return prompt.toString();
    }

    private String buildFallbackExplanation(AnomalyEvent anomaly,
                                           SessionAnalysisDto session,
                                           UserRiskProfileDto risk,
                                           NextActionPredictionDto nextActions,
                                           AnomalyAlertDto activeAnomaly) {
        List<String> evidence = new ArrayList<>();
        if (anomaly.getAnomalyTier() != null) {
            evidence.add("detected as " + anomaly.getAnomalyTier());
        }
        if (anomaly.getAnomalyType() != null && !anomaly.getAnomalyType().isBlank()) {
            evidence.add("classified as `" + anomaly.getAnomalyType() + "`");
        }
        if (anomaly.getAnomalyScore() != null) {
            evidence.add("anomaly score is " + String.format("%.2f", anomaly.getAnomalyScore()));
        }
        if (anomaly.getRuleType() != null && !anomaly.getRuleType().isBlank()) {
            evidence.add("rule engine flagged `" + anomaly.getRuleType() + "`");
        }
        if (session != null && Boolean.TRUE.equals(session.getIsAnomaly())) {
            evidence.add("session summary is already marked anomalous");
        }
        if (risk != null && risk.getRiskTier() != null) {
            evidence.add("insured risk tier is `" + risk.getRiskTier() + "`");
        }
        if (activeAnomaly != null) {
            evidence.add("the insured currently has an active anomaly marker");
        }

        StringBuilder builder = new StringBuilder();
        builder.append("## Assessment\n")
                .append("This anomaly should be reviewed because the current session deviates from the insured's normal behavior profile");
        if (anomaly.getAnomalyType() != null && !anomaly.getAnomalyType().isBlank()) {
            builder.append(" and was classified as `").append(anomaly.getAnomalyType()).append('`');
        }
        builder.append(".\n\n")
                .append("## Evidence\n");
        if (evidence.isEmpty()) {
            builder.append("- The anomaly event exists in the detection pipeline, but supporting context is limited.\n");
        } else {
            for (String item : evidence) {
                builder.append("- ").append(item).append(".\n");
            }
        }

        builder.append("\n## Operational impact\n")
                .append("If legitimate, this may indicate a temporary behavior shift. If malicious, it may represent account misuse or abnormal navigation that deserves a manual review.\n\n")
                .append("## Recommended action\n")
                .append("- Validate the raw event payload and session timeline.\n")
                .append("- Cross-check the insured's recent anomaly history and current risk tier.\n");
        if (nextActions != null && nextActions.getTop3Actions() != null && !nextActions.getTop3Actions().isEmpty()) {
            builder.append("- Compare the observed session outcome with predicted next actions: ")
                    .append(String.join(", ", nextActions.getTop3Actions()))
                    .append(".\n");
        }
        builder.append("- Escalate if the activity affects sensitive flows or if additional alerts arrive for the same insured.\n");
        return builder.toString();
    }

    private String sanitizeJson(Object value) {
        if (value == null) {
            return "n/a";
        }
        try {
            String text;
            if (value instanceof JsonNode node) {
                text = objectMapper.writeValueAsString(node);
            } else if (value instanceof String raw) {
                text = raw;
            } else {
                text = objectMapper.writeValueAsString(value);
            }
            return text.length() > MAX_JSON_CHARS ? text.substring(0, MAX_JSON_CHARS) + "..." : text;
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String orUnknown(Object value) {
        return value == null ? "n/a" : String.valueOf(value);
    }

    private String extractGeminiText(JsonNode response) {
        JsonNode parts = response.at("/candidates/0/content/parts");
        if (!parts.isArray()) {
            return null;
        }

        List<String> chunks = new ArrayList<>();
        for (JsonNode part : parts) {
            String text = part.path("text").asText(null);
            if (text != null && !text.isBlank()) {
                chunks.add(text.trim());
            }
        }

        if (chunks.isEmpty()) {
            return null;
        }
        return String.join("\n\n", chunks);
    }

    private boolean hasRequiredSections(String explanation) {
        if (explanation == null || explanation.isBlank()) {
            return false;
        }

        String normalized = explanation.toLowerCase();
        for (String section : REQUIRED_SECTIONS) {
            if (!normalized.contains(section)) {
                return false;
            }
        }
        return true;
    }

    private String summarizeStats(StatsResponseDto stats) {
        if (stats == null || stats.getPayload() == null) {
            return "n/a";
        }

        JsonNode payload = stats.getPayload();
        if (payload.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            payload.forEach(items::add);
            if (items.isEmpty()) {
                return "no items";
            }

            items.sort((left, right) -> Long.compare(
                    right.path("actualCount").asLong(0L),
                    left.path("actualCount").asLong(0L)
            ));

            List<String> topActions = new ArrayList<>();
            List<String> spikes = new ArrayList<>();
            for (JsonNode item : items) {
                if (topActions.size() < MAX_STATS_ITEMS) {
                    String label = item.path("actionLabel").asText("unknown");
                    long actual = item.path("actualCount").asLong(0L);
                    long predicted = Math.round(item.path("predictedCount").asDouble(0.0));
                    topActions.add(label + "=" + actual + " (pred " + predicted + ")");
                }
                if (spikes.size() < MAX_STATS_ITEMS && item.path("spikeAlert").asBoolean(false)) {
                    spikes.add(item.path("actionLabel").asText("unknown"));
                }
                if (topActions.size() >= MAX_STATS_ITEMS && spikes.size() >= MAX_STATS_ITEMS) {
                    break;
                }
            }

            StringBuilder summary = new StringBuilder();
            summary.append("items=").append(items.size())
                    .append(", source=").append(orUnknown(stats.getSource()))
                    .append(", top=").append(topActions);
            if (!spikes.isEmpty()) {
                summary.append(", spikes=").append(spikes);
            }
            return summary.toString();
        }

        if (payload.isObject() && payload.isEmpty()) {
            return "no data";
        }

        return sanitizeJson(payload);
    }

    private void storeRawGeminiResponse(Long anomalyEventId, String rawResponse) {
        try {
            redisTemplate.opsForValue().set(
                    RAW_RESPONSE_CACHE_PREFIX + anomalyEventId,
                    rawResponse,
                    CACHE_TTL
            );
        } catch (Exception e) {
            log.warn("Failed to cache raw Gemini response for anomaly explanation {}", anomalyEventId, e);
        }
    }
}
