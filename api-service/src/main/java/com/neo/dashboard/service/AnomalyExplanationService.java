package com.neo.dashboard.service;

import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.dto.AnomalyExplanationDto;
import com.neo.dashboard.dto.FeatureContributionDto;
import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.dto.SessionAnalysisDto;
import com.neo.dashboard.dto.StatsResponseDto;
import com.neo.dashboard.dto.UserRiskProfileDto;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.mapper.SessionAnalysisMapper;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.scheduling.annotation.Async;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Builds a human-readable explanation for an anomaly by combining relational
 * data, cache-backed snapshots, and an optional Gemini call.
 */
@Service
@Slf4j
public class AnomalyExplanationService {

    /* Cache policy for final explanations and raw AI responses. */
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final String CACHE_PREFIX = "ai:explanation:anomaly:";
    private static final String RAW_RESPONSE_CACHE_PREFIX = "ai:explanation:anomaly:raw:";

    /* Output limits used when calling Gemini. */
    private static final int MAX_OUTPUT_TOKENS = 2048;
    private static final int THINKING_BUDGET = 0;
    private static final Duration GEMINI_TIMEOUT = Duration.ofSeconds(10);

    /* Prompt-shaping limits that keep context concise and predictable. */
    private static final int MAX_JSON_CHARS = 400;
    private static final int MAX_STATS_ITEMS = 3;
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "assessment",
            "evidence",
            "operational impact",
            "recommended action"
    );

    /* Dependencies used to gather surrounding context for one anomaly event. */
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final AnomalyEventRepository anomalyEventRepository;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final SessionAnalysisMapper sessionAnalysisMapper;
    private final RiskProfileService riskProfileService;
    private final NextActionPredictionService nextActionPredictionService;
    private final ActiveAnomalyService activeAnomalyService;
    private final SessionInsightReadService sessionInsightReadService;
    private final StatsService statsService;

    @Value("${spring.ai.google.genai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.google.genai.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}")
    private String model;

    public AnomalyExplanationService(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     AnomalyEventRepository anomalyEventRepository,
                                     SessionAnalysisRepository sessionAnalysisRepository,
                                     SessionAnalysisMapper sessionAnalysisMapper,
                                     RiskProfileService riskProfileService,
                                     NextActionPredictionService nextActionPredictionService,
                                     ActiveAnomalyService activeAnomalyService,
                                     SessionInsightReadService sessionInsightReadService,
                                     StatsService statsService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.anomalyEventRepository = anomalyEventRepository;
        this.sessionAnalysisRepository = sessionAnalysisRepository;
        this.sessionAnalysisMapper = sessionAnalysisMapper;
        this.riskProfileService = riskProfileService;
        this.nextActionPredictionService = nextActionPredictionService;
        this.activeAnomalyService = activeAnomalyService;
        this.sessionInsightReadService = sessionInsightReadService;
        this.statsService = statsService;
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector())
                .build();
    }

    @Async
    public CompletableFuture<Optional<AnomalyExplanationDto>> explainAsync(Long anomalyEventId, boolean refresh) {
        return CompletableFuture.completedFuture(explain(anomalyEventId, refresh));
    }

    /*
     * Return a cached explanation when possible, otherwise rebuild it from
     * contextual data.  No blanket @Transactional here: the method fans out to
     * multiple services that each manage their own transaction scope, and the
     * Redis-only paths should never acquire a JDBC connection.
     */
    public Optional<AnomalyExplanationDto> explain(Long anomalyEventId, boolean refresh) {
        Optional<AnomalyEvent> anomalyOpt = anomalyEventRepository.findById(anomalyEventId);
        if (anomalyOpt.isEmpty()) {
            return Optional.empty();
        }

        AnomalyEvent anomaly = anomalyOpt.get();

        // Gather the neighboring session, risk, prediction, alert, and stats context for prompt construction.
        SessionAnalysisDto session = sessionAnalysisRepository
                .findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anomaly.getInsuredId(), anomaly.getSessionId())
                .map(sessionAnalysisMapper::toDto)
                .orElse(null);
        JsonNode liveSessionInsight = sessionInsightReadService
                .getInsight(anomaly.getInsuredId(), anomaly.getSessionId())
                .orElse(null);
        UserRiskProfileDto risk = riskProfileService.getRiskProfile(anomaly.getInsuredId()).orElse(null);
        NextActionPredictionDto nextActions = nextActionPredictionService.getPrediction(anomaly.getInsuredId()).orElse(null);
        AnomalyAlertDto activeAnomaly = activeAnomalyService.getActiveAnomaly(anomaly.getInsuredId()).orElse(null);
        StatsResponseDto liveStats = statsService.getLiveStats(LocalDate.now());
        StatsResponseDto trendStats = statsService.getTrendStats(LocalDate.now());

        if (!refresh) {
            AnomalyExplanationDto precomputed = resolvePrecomputedExplanation(anomalyEventId, anomaly, session, liveSessionInsight);
            if (precomputed != null) {
                cache(precomputed);
                return Optional.of(precomputed);
            }

            // Reuse a complete cached explanation only after checking the durable SQL explainability first.
            AnomalyExplanationDto cached = getCached(anomalyEventId);
            if (cached != null) {
                cached.setCached(true);
                return Optional.of(cached);
            }
        }

        // Ask Gemini first, then fall back to a deterministic local explanation when AI is unavailable.
        String prompt = buildPrompt(anomaly, session, liveSessionInsight, risk, nextActions, activeAnomaly, liveStats, trendStats);
        String aiText = generateWithGemini(anomalyEventId, prompt);
        String source = aiText == null || aiText.isBlank() ? "heuristic" : "gemini";
        String explanation = (aiText == null || aiText.isBlank())
                ? buildFallbackExplanation(anomaly, session, liveSessionInsight, risk, nextActions, activeAnomaly)
                : aiText.trim();

        AnomalyExplanationDto dto = new AnomalyExplanationDto(
                anomalyEventId,
                source,
                "heuristic".equals(source) ? "local-fallback" : model,
                Instant.now(),
                false,
                explanation
        );

        // Cache the normalized DTO so later requests can return instantly.
        cache(dto);
        return Optional.of(dto);
    }

    /* Read a cached explanation and reject incomplete content that would confuse the UI. */
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

    /* Persist the final explanation for future reads. */
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

    /* Call Gemini directly through the REST API and validate the returned markdown structure. */
    private String generateWithGemini(Long anomalyEventId, String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            // The service can still function without AI by using the heuristic fallback.
            return null;
        }

        try {
            // Build the request body
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

            // Make the async call with timeout
            JsonNode response = webClient.post()
                    .uri(baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(GEMINI_TIMEOUT)
                    .onErrorResume(TimeoutException.class, e -> {
                        log.warn("Gemini API call timed out after {} seconds for anomaly {}", GEMINI_TIMEOUT.toSeconds(), anomalyEventId);
                        return Mono.empty();
                    })
                    .onErrorResume(e -> {
                        log.warn("Gemini API call failed for anomaly {}", anomalyEventId, e);
                        return Mono.empty();
                    })
                    .block(GEMINI_TIMEOUT.plusSeconds(1));
            if (response == null) {
                return null;
            }
            storeRawGeminiResponse(anomalyEventId, response.toString());
            String text = extractGeminiText(response);
            return (text == null || text.isBlank()) ? null : text;

        } catch (Exception e) {
            log.warn("Gemini API call failed for anomaly {}", anomalyEventId, e);
            return null;
        }
    }

    /* Assemble the compact operational prompt sent to Gemini. */
    private String buildPrompt(AnomalyEvent anomaly,
                               SessionAnalysisDto session,
                               JsonNode liveSessionInsight,
                               UserRiskProfileDto risk,
                               NextActionPredictionDto nextActions,
                               AnomalyAlertDto activeAnomaly,
                               StatsResponseDto liveStats,
                               StatsResponseDto trendStats) {
        JsonNode eventContext = parseEventContext(anomaly);
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
                .append("- Anomaly probability: ").append(orUnknown(anomaly.getAnomalyProbability())).append('\n')
                .append("- Confidence: ").append(orUnknown(anomaly.getTypeConfidence())).append('\n')
                .append("- Churn probability: ").append(orUnknown(anomaly.getChurnProbability())).append('\n')
                .append("- Risk score: ").append(orUnknown(anomaly.getRiskScore())).append('\n')
                .append("- Path deviation: ").append(orUnknown(anomaly.getPathDeviation())).append('\n')
                .append("- Triggered rules: ").append(orUnknown(anomaly.getRuleType())).append('\n')
                .append("- Event time: ").append(orUnknown(anomaly.getEventTime())).append('\n')
                .append("- Detected at: ").append(orUnknown(anomaly.getDetectedAt())).append('\n')
                .append("- Alert context: ").append(sanitizeJson(eventContext)).append("\n\n");

        prompt.append("Session summary:\n")
                .append("- Available: ").append(session != null).append('\n');
        if (session != null) {
            prompt.append("- Total events: ").append(orUnknown(session.getTotalEvents())).append('\n')
                    .append("- Duration seconds: ").append(orUnknown(session.getSessionDurationSeconds())).append('\n')
                    .append("- Unique actions: ").append(orUnknown(session.getUniqueActions())).append('\n')
                    .append("- KO rate: ").append(orUnknown(session.getKoRate())).append('\n')
                    .append("- ISO / tabular anomaly score: ").append(orUnknown(session.getIsoScore())).append('\n')
                    .append("- Ensemble risk: ").append(orUnknown(session.getEnsembleRiskScore())).append('\n')
                    .append("- Churn probability: ").append(orUnknown(session.getChurnProbability())).append('\n')
                    .append("- Path deviation: ").append(orUnknown(session.getPathDeviation())).append('\n')
                    .append("- Session anomaly flag: ").append(orUnknown(session.getIsAnomaly())).append('\n')
                    .append("- Session anomaly type: ").append(orUnknown(session.getAnomalyType())).append('\n')
                    .append("- Rule type: ").append(orUnknown(session.getRuleType())).append('\n')
                    .append("- Top next actions: ").append(session.getTop3NextActions()).append('\n')
                    .append("- Context tags: ").append(orUnknown(session.getContextTags())).append('\n')
                    .append("- Triggered rules: ").append(orUnknown(session.getTriggeredRules())).append('\n')
                    .append("- Warnings: ").append(orUnknown(session.getWarnings())).append('\n')
                    .append("- Rare transitions: ").append(orUnknown(session.getRareTransitions())).append('\n')
                    .append("- Action sequence: ").append(orUnknown(session.getActionSequence())).append('\n')
                    .append("- Route sequence: ").append(orUnknown(session.getRouteSequence())).append('\n')
                    .append("- Top contributing features: ").append(orUnknown(summarizeFeatures(session.getTopContributingFeatures()))).append('\n');
        }

        prompt.append("\nLive session insight:\n")
                .append("- Available: ").append(liveSessionInsight != null).append('\n');
        if (liveSessionInsight != null) {
            prompt.append("- Risk level: ").append(orUnknown(textAt(liveSessionInsight, "riskLevel"))).append('\n')
                    .append("- Context tags: ").append(sanitizeJson(liveSessionInsight.path("contextTags"))).append('\n')
                    .append("- Triggered rules: ").append(sanitizeJson(liveSessionInsight.path("triggeredRules"))).append('\n')
                    .append("- Warnings: ").append(sanitizeJson(liveSessionInsight.path("warnings"))).append('\n')
                    .append("- Rare transitions: ").append(sanitizeJson(liveSessionInsight.path("rareTransitions"))).append('\n')
                    .append("- Action sequence: ").append(sanitizeJson(liveSessionInsight.path("actionSequence"))).append('\n')
                    .append("- Route sequence: ").append(sanitizeJson(liveSessionInsight.path("routeSequence"))).append('\n')
                    .append("- Top contributing features: ").append(sanitizeJson(liveSessionInsight.path("topContributingFeatures"))).append('\n');
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

    /* Build a deterministic explanation when AI is disabled or the response is unusable. */
    private String buildFallbackExplanation(AnomalyEvent anomaly,
                                             SessionAnalysisDto session,
                                             JsonNode liveSessionInsight,
                                             UserRiskProfileDto risk,
                                             NextActionPredictionDto nextActions,
                                             AnomalyAlertDto activeAnomaly) {
        List<String> evidence = new ArrayList<>();
        JsonNode eventContext = parseEventContext(anomaly);
        if (anomaly.getAnomalyTier() != null) {
            evidence.add("detected as " + anomaly.getAnomalyTier());
        }
        if (anomaly.getAnomalyType() != null && !anomaly.getAnomalyType().isBlank()) {
            evidence.add("classified as `" + anomaly.getAnomalyType() + "`");
        }
        if (anomaly.getAnomalyScore() != null) {
            evidence.add("anomaly score is " + String.format("%.2f", anomaly.getAnomalyScore()));
        }
        if (anomaly.getAnomalyProbability() != null) {
            evidence.add("model anomaly probability is " + String.format("%.2f", anomaly.getAnomalyProbability()));
        }
        if (anomaly.getChurnProbability() != null) {
            evidence.add("churn probability is " + String.format("%.2f", anomaly.getChurnProbability()));
        }
        if (Boolean.TRUE.equals(anomaly.getPathDeviation())) {
            evidence.add("the latest action transition was a low-probability (path deviation) step");
        }
        if (anomaly.getRuleType() != null && !anomaly.getRuleType().isBlank()) {
            evidence.add("rule engine flagged `" + anomaly.getRuleType() + "`");
        }
        if (session != null && Boolean.TRUE.equals(session.getIsAnomaly())) {
            evidence.add("session summary is already marked anomalous");
        }
        if (session != null && session.getTopContributingFeatures() != null && !session.getTopContributingFeatures().isEmpty()) {
            evidence.add("top contributing features include " + summarizeFeatures(session.getTopContributingFeatures()));
        }
        if (session != null && session.getContextTags() != null && !session.getContextTags().isEmpty()) {
            evidence.add("session context tags are " + session.getContextTags());
        }
        if (risk != null && risk.getRiskTier() != null) {
            evidence.add("insured risk tier is `" + risk.getRiskTier() + "`");
        }
        if (activeAnomaly != null) {
            evidence.add("the insured currently has an active anomaly marker");
        }
        if (liveSessionInsight != null && !liveSessionInsight.path("contextTags").isMissingNode()) {
            evidence.add("live session context shows " + sanitizeJson(liveSessionInsight.path("contextTags")));
        }
        if (eventContext != null && !eventContext.isMissingNode() && !eventContext.isEmpty()) {
            evidence.add("alert context includes " + sanitizeJson(eventContext));
        }

        StringBuilder builder = new StringBuilder();
        builder.append("## Assessment\n")
                .append("Explanation temporarily unavailable from Gemini. ")
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
                .append("- Validate the compact alert context and session timeline.\n")
                .append("- Cross-check the insured's recent anomaly history and current risk tier.\n");
        if (nextActions != null && nextActions.getTop3Actions() != null && !nextActions.getTop3Actions().isEmpty()) {
            builder.append("- Compare the observed session outcome with predicted next actions: ")
                    .append(String.join(", ", nextActions.getTop3Actions()))
                    .append(".\n");
        }
        builder.append("- Escalate if the activity affects sensitive flows or if additional alerts arrive for the same insured.\n");
        return builder.toString();
    }

    private AnomalyExplanationDto resolvePrecomputedExplanation(Long anomalyEventId,
                                                               AnomalyEvent anomaly,
                                                               SessionAnalysisDto session,
                                                               JsonNode liveSessionInsight) {
        if (session != null && hasText(session.getExplainabilityText())) {
            return new AnomalyExplanationDto(
                    anomalyEventId,
                    "session-analysis",
                    "data-processor",
                    Instant.now(),
                    false,
                    session.getExplainabilityText().trim()
            );
        }

        String liveInsightText = textAt(liveSessionInsight, "explainabilityText");
        if (hasText(liveInsightText)) {
            return new AnomalyExplanationDto(
                    anomalyEventId,
                    "session-insight",
                    "data-processor",
                    Instant.now(),
                    false,
                    liveInsightText.trim()
            );
        }

        String eventContextText = textAt(parseEventContext(anomaly), "explainabilityText");
        if (hasText(eventContextText)) {
            return new AnomalyExplanationDto(
                    anomalyEventId,
                    "anomaly-context",
                    "data-processor",
                    Instant.now(),
                    false,
                    eventContextText.trim()
            );
        }

        return null;
    }

    /* Trim large payloads before embedding them in prompts or logs. */
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

    /* Convert null values to a readable placeholder for prompt construction. */
    private String orUnknown(Object value) {
        return value == null ? "n/a" : String.valueOf(value);
    }

    private JsonNode parseEventContext(AnomalyEvent anomaly) {
        if (anomaly == null || anomaly.getEventJson() == null || anomaly.getEventJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(anomaly.getEventJson());
        } catch (Exception e) {
            log.warn("Failed to parse compact anomaly context for event {}", anomaly.getId(), e);
            return null;
        }
    }

    private String textAt(JsonNode node, String fieldName) {
        if (node == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        String value = node.path(fieldName).asText(null);
        return hasText(value) ? value : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String summarizeFeatures(List<FeatureContributionDto> contributions) {
        if (contributions == null || contributions.isEmpty()) {
            return null;
        }
        return contributions.stream()
                .filter(item -> item != null && hasText(item.getFeature()))
                .limit(3)
                .map(item -> item.getFeature() + "=" + orUnknown(item.getActualValue()))
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
    }

    /* Extract plain text chunks from Gemini's nested candidate payload. */
    private String extractGeminiText(JsonNode response) {
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

        if (chunks.isEmpty()) {
            return null;
        }
        return String.join("\n\n", chunks);
    }

    /* Ensure the explanation contains every section expected by the frontend. */
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

    /* Compress stats payloads into a short natural-language summary for the AI prompt. */
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
            if (looksLikeForecastSeries(items.get(0))) {
                JsonNode latest = items.get(items.size() - 1);
                return "points=" + items.size()
                        + ", source=" + orUnknown(stats.getSource())
                        + ", latest=" + orUnknown(latest.path("ds").asText(null))
                        + ", expected=" + orUnknown(numberOrNull(latest, "yhat"))
                        + ", lower=" + orUnknown(numberOrNull(latest, "yhatLower", "yhat_lower"))
                        + ", upper=" + orUnknown(numberOrNull(latest, "yhatUpper", "yhat_upper"));
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

    private boolean looksLikeForecastSeries(JsonNode node) {
        return node != null
                && (node.has("ds")
                || node.has("yhat")
                || node.has("yhatLower")
                || node.has("yhat_lower")
                || node.has("yhatUpper")
                || node.has("yhat_upper"));
    }

    private Double numberOrNull(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.isBlank() || !node.has(fieldName) || node.path(fieldName).isNull()) {
                continue;
            }
            return node.path(fieldName).asDouble();
        }
        return null;
    }

    /* Store the raw Gemini payload separately to support debugging prompt or parsing issues. */
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
