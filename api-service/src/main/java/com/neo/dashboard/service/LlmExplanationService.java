package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36LlmExplanationRequest;
import com.neo.dashboard.dto.v36.V36LlmExplanationResponse;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.service.llm.LlmProvider;
import com.neo.dashboard.service.llm.LlmProviderRequest;
import com.neo.dashboard.service.llm.LlmProviderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmExplanationService {

    private static final String DISCLAIMER = "Generated from model and rule evidence. Analyst aid only.";
    private static final String SOURCE_GENERATED = "generated";
    private static final String SOURCE_FORCE_GENERATED = "force_generated";
    private static final String SOURCE_PROVIDER_ERROR_FALLBACK = "provider_error_fallback";
    private static final String SOURCE_PROVIDER_PARSE_FALLBACK = "provider_parse_fallback";

    private final LlmEvidenceReadService evidenceReadService;
    private final LlmPromptBuilderV36 promptBuilder;
    private final LlmExplanationCacheService cacheService;
    private final LlmProvider llmProvider;
    private final ObjectMapper objectMapper;

    @Value("${app.v36.explanations.default-style:security_analyst}")
    private String defaultStyle;

    @Value("${app.v36.explanations.default-language:en}")
    private String defaultLanguage;

    @Value("${app.v36.explanations.lock-ttl-seconds:30}")
    private long lockTtlSeconds;

    public Optional<V36LlmExplanationResponse> getCached(String eventId) {
        if (eventId == null) {
            return Optional.empty();
        }
        return cacheService.getLatest(eventId);
    }

    public V36LlmExplanationResponse generate(String eventId, V36LlmExplanationRequest request) {
        boolean forceRefresh = request != null && Boolean.TRUE.equals(request.getForceRefresh());
        String style = safe(request == null ? null : request.getStyle(), defaultStyle);
        String language = safe(request == null ? null : request.getLanguage(), defaultLanguage);
        boolean includeActions = request == null || !Boolean.FALSE.equals(request.getIncludeRecommendedActions());

        if (!forceRefresh) {
            Optional<V36LlmExplanationResponse> cached = cacheService.getLatest(eventId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        JsonNode evidence = evidenceReadService.readEvidence(eventId)
                .orElseThrow(() -> {
                    log.warn("LLM_EXPLANATION_SKIPPED_NO_EVIDENCE eventId={}", eventId);
                    return new ApiException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND",
                            "V3.6.1 LLM evidence payload not found for event " + eventId);
                });
        if (!evidenceReadService.isV36Evidence(evidence)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE_SCHEMA",
                    "LLM evidence payload is not schemaVersion v3.6.1");
        }

        if (!tryAcquireLock(eventId, language, style)) {
            if (!forceRefresh) {
                Optional<V36LlmExplanationResponse> retryCache = cacheService.getLatest(eventId);
                if (retryCache.isPresent()) {
                    return retryCache.get();
                }
            }
            throw new ApiException(HttpStatus.CONFLICT, "GENERATION_IN_PROGRESS",
                    "LLM explanation generation already in progress for event " + eventId);
        }

        try {
            return doGenerate(eventId, evidence, forceRefresh, style, language, includeActions);
        } finally {
            cacheService.releaseLock(eventId, language, style);
        }
    }

    private V36LlmExplanationResponse doGenerate(String eventId, JsonNode evidence,
                                                  boolean forceRefresh, String style, String language,
                                                  boolean includeActions) {
        String evidenceHash = evidenceReadService.evidenceHash(evidence);
        String llmRequestId = UUID.randomUUID().toString();

        if (!forceRefresh) {
            Optional<V36LlmExplanationResponse> cached = cacheService.get(eventId, evidenceHash, style, language);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        String systemPrompt = promptBuilder.buildSystemPrompt(style, language, includeActions);
        String userPrompt = promptBuilder.buildPrompt(evidence, style, language, includeActions);

        log.info("LLM_PROMPT_BUILT llmRequestId={} eventId={} evidenceHash={} systemChars={} userChars={} compacted=true",
                llmRequestId, eventId, evidenceHash, systemPrompt.length(), userPrompt.length());

        LlmProviderRequest request = LlmProviderRequest.builder()
                .llmRequestId(llmRequestId)
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .eventId(eventId)
                .evidenceHash(evidenceHash)
                .style(style)
                .language(language)
                .includeRecommendedActions(includeActions)
                .build();

        LlmProviderResponse llmResponse = llmProvider.generate(request);

        if (llmResponse.isSuccess()) {
            if ("length".equals(llmResponse.getFinishReason())) {
                V36LlmExplanationResponse retryResponse = attemptRetry(eventId, evidence, evidenceHash, style, language,
                        includeActions, forceRefresh, llmRequestId, llmResponse);
                if (retryResponse != null) return retryResponse;
            }

            V36LlmExplanationResponse response = buildLlmResponse(llmResponse, evidence, eventId, evidenceHash, style, language, forceRefresh, llmRequestId);
            log.info("LLM_EXPLANATION_{} llmRequestId={} provider={} model={} eventId={} fallback={} finishReason={}",
                    forceRefresh ? "FORCE_GENERATE" : "GENERATE",
                    llmRequestId, llmResponse.getProvider(), llmResponse.getModel(), eventId,
                    response.getFallback(), response.getFinishReason());
            if (!Boolean.TRUE.equals(response.getFallback())) {
                cacheService.put(response);
                log.info("LLM_EXPLANATION_STORED llmRequestId={} eventId={}", llmRequestId, eventId);
            }
            return response;
        }

        V36LlmExplanationResponse fallback = buildFallbackResponse(eventId, evidenceHash, style, language, evidence, forceRefresh, llmRequestId);
        log.warn("LLM_EXPLANATION_PROVIDER_ERROR llmRequestId={} eventId={} errorCode={} errorMessage={}",
                llmRequestId, eventId, llmResponse.getErrorCode(), llmResponse.getErrorMessage());
        return fallback;
    }

    private V36LlmExplanationResponse attemptRetry(String eventId, JsonNode evidence, String evidenceHash,
                                                    String style, String language, boolean includeActions,
                                                    boolean forceRefresh, String llmRequestId,
                                                    LlmProviderResponse originalResponse) {
        log.info("LLM_EXPLANATION_RETRY llmRequestId={} eventId={} reason=finish_reason_length " +
                        "completionTokens={} maxTokens={}",
                llmRequestId, eventId, originalResponse.getCompletionTokens(), 4096);

        String retryPrompt = promptBuilder.buildRetryPrompt(evidence, style, language, includeActions);

        LlmProviderRequest retryRequest = LlmProviderRequest.builder()
                .llmRequestId(llmRequestId)
                .systemPrompt(buildRetrySystemPrompt())
                .userPrompt(retryPrompt)
                .eventId(eventId)
                .evidenceHash(evidenceHash)
                .style(style)
                .language(language)
                .includeRecommendedActions(includeActions)
                .build();

        LlmProviderResponse retryResponse = llmProvider.generate(retryRequest);

        if (retryResponse.isSuccess()) {
            V36LlmExplanationResponse response = buildLlmResponse(retryResponse, evidence, eventId, evidenceHash, style, language, forceRefresh, llmRequestId);
            if (!Boolean.TRUE.equals(response.getFallback())) {
                cacheService.put(response);
                log.info("LLM_EXPLANATION_STORED llmRequestId={} eventId={} (retry)", llmRequestId, eventId);
                log.info("LLM_EXPLANATION_RETRY_SUCCESS llmRequestId={} eventId={}", llmRequestId, eventId);
                return response;
            }
        }

        log.warn("LLM_EXPLANATION_RETRY_FAILED llmRequestId={} eventId={} retryFinishReason={}",
                llmRequestId, eventId,
                retryResponse != null ? retryResponse.getFinishReason() : "no_response");
        return buildTruncatedFallbackResponse(eventId, evidenceHash, style, language, evidence, forceRefresh, llmRequestId);
    }

    private String buildRetrySystemPrompt() {
        return """
                You are a senior cybersecurity analyst assistant.
                Return compact JSON only. Under 450 words.
                Do not use markdown.
                Do not include reasoning or planning.
                The first character must be { and the last must be }.
                """;
    }

    private V36LlmExplanationResponse buildLlmResponse(LlmProviderResponse llmResponse,
                                                        JsonNode evidence,
                                                        String eventId,
                                                        String evidenceHash,
                                                        String style,
                                                        String language,
                                                        boolean forceRefresh,
                                                        String llmRequestId) {
        V36LlmExplanationResponse response = new V36LlmExplanationResponse();
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        response.setEventId(eventId);
        response.setGeneratedAt(Instant.now());
        response.setCached(false);
        response.setForceRefresh(forceRefresh);
        response.setEvidenceHash(evidenceHash);
        response.setStyle(style);
        response.setLanguage(language);
        response.setProvider(llmResponse.getProvider());
        response.setModel(llmResponse.getModel());
        response.setFinishReason(llmResponse.getFinishReason());
        response.setLlmRequestId(llmRequestId);

        String rawText = llmResponse.getText();
        String finishReason = llmResponse.getFinishReason();

        if (rawText == null || rawText.isBlank()) {
            response.setSummary("LLM returned empty response");
            response.setFallback(true);
            response.setSource(forceRefresh ? SOURCE_FORCE_GENERATED : SOURCE_GENERATED);
            fillFromEvidence(response, evidence);

        } else if (tryParseStructuredResponse(rawText, response)) {
            response.setFallback(false);
            response.setSource(forceRefresh ? SOURCE_FORCE_GENERATED : SOURCE_GENERATED);

        } else {
            response.setFallback(true);
            response.setSource(SOURCE_PROVIDER_PARSE_FALLBACK);
            response.setRawProviderResponse(rawText);
            response.setSummary(buildParseFallbackSummary(evidence));
            addWarning(response, "LLM response was not valid structured JSON");
            fillFromEvidence(response, evidence);
        }

        if (response.getDisclaimer() == null) {
            response.setDisclaimer(DISCLAIMER);
        }
        return response;
    }

    private boolean tryParseStructuredResponse(String text, V36LlmExplanationResponse response) {
        String json = extractJsonObject(text);
        if (json == null) {
            return false;
        }
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (!parsed.isObject()) {
                return false;
            }
            response.setSummary(textAt(parsed, "/summary"));

            // Backward-compat: populate old narrative fields if present
            response.setRiskNarrative(textAt(parsed, "/riskNarrative"));
            response.setBehaviorNarrative(textAt(parsed, "/behaviorNarrative"));
            response.setModelNarrative(textAt(parsed, "/modelNarrative"));
            response.setRulesNarrative(textAt(parsed, "/rulesNarrative"));
            response.setSequenceNarrative(textAt(parsed, "/sequenceNarrative"));

            JsonNode bullets = parsed.path("keyEvidenceBullets");
            if (!bullets.isArray() || bullets.isEmpty()) {
                bullets = parsed.path("evidenceBullets");
            }
            response.setEvidenceBullets(parseStringList(bullets));
            response.setPossibleInterpretation(textAt(parsed, "/possibleInterpretation"));
            response.setRecommendedActions(parseStringList(parsed.path("recommendedActions")));
            response.setLimitations(parseStringList(parsed.path("limitations")));

            // Compact schema: modelAnalysis -> modelScoreExplanation
            JsonNode modelAnalysis = parsed.path("modelAnalysis");
            if (modelAnalysis.isObject()) {
                Map<String, Object> analysisMap = new LinkedHashMap<>();
                modelAnalysis.fields().forEachRemaining(entry ->
                        analysisMap.put(entry.getKey(),
                                entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString()));
                response.setModelScoreExplanation(analysisMap);
            }

            // Legacy: modelScoreExplanation directly
            JsonNode scores = parsed.path("modelScoreExplanation");
            if (scores.isObject() && response.getModelScoreExplanation() == null) {
                Map<String, Object> scoreMap = new LinkedHashMap<>();
                scores.fields().forEachRemaining(entry -> scoreMap.put(entry.getKey(),
                        entry.getValue().isNumber() ? entry.getValue().numberValue() : entry.getValue().asText()));
                response.setModelScoreExplanation(scoreMap);
            }

            // Legacy: triggeredRulesExplanation
            JsonNode triggeredRules = parsed.path("triggeredRulesExplanation");
            if (triggeredRules.isArray() && !triggeredRules.isEmpty()) {
                List<Map<String, Object>> rulesList = new ArrayList<>();
                for (JsonNode item : triggeredRules) {
                    if (item.isObject()) {
                        Map<String, Object> ruleMap = new LinkedHashMap<>();
                        item.fields().forEachRemaining(entry ->
                                ruleMap.put(entry.getKey(),
                                        entry.getValue().isNumber() ? entry.getValue().numberValue() : entry.getValue().asText()));
                        rulesList.add(ruleMap);
                    }
                }
                if (!rulesList.isEmpty()) {
                    response.setTriggeredRulesExplanation(rulesList);
                }
            }

            String disclaimer = textAt(parsed, "/disclaimer");
            if (disclaimer != null) {
                response.setDisclaimer(disclaimer);
            }

            return response.getSummary() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String val = item.asText(null);
                if (val != null && !val.isBlank()) {
                    list.add(val.trim());
                }
            }
        }
        return list.isEmpty() ? null : list;
    }

    private V36LlmExplanationResponse buildFallbackResponse(String eventId,
                                                             String evidenceHash,
                                                             String style,
                                                             String language,
                                                             JsonNode evidence,
                                                             boolean forceRefresh,
                                                             String llmRequestId) {
        V36LlmExplanationResponse response = new V36LlmExplanationResponse();
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        response.setEventId(eventId);
        response.setGeneratedAt(Instant.now());
        response.setCached(false);
        response.setForceRefresh(forceRefresh);
        response.setEvidenceHash(evidenceHash);
        response.setStyle(style);
        response.setLanguage(language);
        response.setProvider("heuristic");
        response.setModel("local-fallback");
        response.setSource(SOURCE_PROVIDER_ERROR_FALLBACK);
        response.setFallback(true);
        response.setSummary(buildFallbackSummary(evidence));
        response.setLlmRequestId(llmRequestId);
        addWarning(response, "LLM provider returned error; used deterministic fallback summary");
        fillFromEvidence(response, evidence);
        response.setDisclaimer(DISCLAIMER);
        return response;
    }

    private V36LlmExplanationResponse buildTruncatedFallbackResponse(String eventId,
                                                                      String evidenceHash,
                                                                      String style,
                                                                      String language,
                                                                      JsonNode evidence,
                                                                      boolean forceRefresh,
                                                                      String llmRequestId) {
        V36LlmExplanationResponse response = new V36LlmExplanationResponse();
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        response.setEventId(eventId);
        response.setGeneratedAt(Instant.now());
        response.setCached(false);
        response.setForceRefresh(forceRefresh);
        response.setEvidenceHash(evidenceHash);
        response.setStyle(style);
        response.setLanguage(language);
        response.setProvider("nvidia-nim");
        response.setModel("nvidia/nemotron-3-super-120b-a12b");
        response.setSource(SOURCE_PROVIDER_PARSE_FALLBACK);
        response.setFallback(true);
        response.setFinishReason("length");
        response.setSummary(buildTruncatedFallbackSummary(evidence));
        response.setLlmRequestId(llmRequestId);
        addWarning(response, "LLM response was truncated before valid JSON was completed even after retry");
        fillFromEvidence(response, evidence);
        response.setDisclaimer(DISCLAIMER);
        return response;
    }

    private void fillFromEvidence(V36LlmExplanationResponse response, JsonNode evidence) {
        if (response.getEvidenceBullets() == null) {
            response.setEvidenceBullets(evidenceBullets(evidence));
        }
        if (response.getPossibleInterpretation() == null) {
            response.setPossibleInterpretation(possibleInterpretation(evidence));
        }
        if (response.getRecommendedActions() == null) {
            response.setRecommendedActions(recommendedActions(evidence));
        }
        if (response.getModelScoreExplanation() == null) {
            response.setModelScoreExplanation(modelScoreExplanation(evidence));
        }
    }

    private String buildFallbackSummary(JsonNode evidence) {
        String riskLevel = textAt(evidence, "/risk/riskLevel");
        String finalRiskScore = textAt(evidence, "/risk/finalRiskScore");
        String anomalyType = textAt(evidence, "/anomalyTypeAttribution/anomalyType");
        return "LLM provider unavailable. The event is flagged from V3.6.1 evidence"
                + (riskLevel == null ? "" : " with risk level " + riskLevel)
                + (finalRiskScore == null ? "" : " and final risk score " + finalRiskScore)
                + (anomalyType == null ? "." : " for anomaly type " + anomalyType + ".");
    }

    private String buildParseFallbackSummary(JsonNode evidence) {
        String riskLevel = textAt(evidence, "/risk/riskLevel");
        String finalRiskScore = textAt(evidence, "/risk/finalRiskScore");
        String anomalyType = textAt(evidence, "/anomalyTypeAttribution/anomalyType");
        String action = textAt(evidence, "/eventMetadata/eventAction");
        List<String> topContributors = extractTopContributorNames(evidence);

        StringBuilder sb = new StringBuilder();
        sb.append("The session was flagged");
        if (riskLevel != null) sb.append(" as ").append(riskLevel);
        sb.append(" risk");
        if (finalRiskScore != null) sb.append(" with a final fused score of ").append(finalRiskScore);
        sb.append(".");
        if (!topContributors.isEmpty()) {
            sb.append(" The strongest contributors were ").append(formatContributorList(topContributors)).append(".");
        }
        if (action != null) {
            sb.append(" The event action was \"").append(action).append("\".");
        }
        if (anomalyType != null) {
            sb.append(" The anomaly type is ").append(anomalyType).append(".");
        }
        sb.append(" The AI provider returned an invalid structured response, so this explanation was generated from deterministic evidence.");
        return sb.toString();
    }

    private String buildTruncatedFallbackSummary(JsonNode evidence) {
        String riskLevel = textAt(evidence, "/risk/riskLevel");
        String finalRiskScore = textAt(evidence, "/risk/finalRiskScore");
        String anomalyType = textAt(evidence, "/anomalyTypeAttribution/anomalyType");
        String action = textAt(evidence, "/eventMetadata/eventAction");
        List<String> topContributors = extractTopContributorNames(evidence);

        StringBuilder sb = new StringBuilder();
        sb.append("The session was flagged");
        if (riskLevel != null) sb.append(" as ").append(riskLevel);
        sb.append(" risk");
        if (finalRiskScore != null) sb.append(" with a final fused score of ").append(finalRiskScore);
        sb.append(".");
        if (!topContributors.isEmpty()) {
            sb.append(" The strongest contributors were ").append(formatContributorList(topContributors)).append(".");
        }
        if (action != null) {
            sb.append(" The event action was \"").append(action).append("\".");
        }
        if (anomalyType != null) {
            sb.append(" The anomaly type is ").append(anomalyType).append(".");
        }
        sb.append(" The AI provider response was truncated before a valid structured explanation could be completed.");
        return sb.toString();
    }

    private List<String> extractTopContributorNames(JsonNode evidence) {
        JsonNode mc = evidence.path("modelContributions");
        if (!mc.isObject()) return List.of();

        List<Map.Entry<String, BigDecimal>> entries = new ArrayList<>();
        mc.fields().forEachRemaining(entry -> {
            JsonNode v = entry.getValue();
            if (v.isNumber() && v.decimalValue().compareTo(BigDecimal.ZERO) > 0) {
                entries.add(Map.entry(entry.getKey(), v.decimalValue()));
            }
        });
        entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        return entries.stream()
                .map(e -> {
                    switch (e.getKey()) {
                        case "xgboost": return "XGBoost";
                        case "lightgbm": return "LightGBM";
                        case "transformer": return "Transformer";
                        case "tcn": return "TCN";
                        case "rules": return "deterministic rules";
                        case "businessContext": return "business context";
                        default: return e.getKey();
                    }
                })
                .limit(4)
                .collect(Collectors.toList());
    }

    private String formatContributorList(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        if (names.size() == 2) return names.get(0) + " and " + names.get(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size() - 1; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        sb.append(", and ").append(names.get(names.size() - 1));
        return sb.toString();
    }

    private boolean tryAcquireLock(String eventId, String language, String style) {
        return cacheService.tryAcquireLock(eventId, language, style, Duration.ofSeconds(lockTtlSeconds));
    }

    private List<String> evidenceBullets(JsonNode evidence) {
        List<String> bullets = new ArrayList<>();
        addIfPresent(bullets, "Risk level", textAt(evidence, "/risk/riskLevel"));
        addIfPresent(bullets, "Final risk score", textAt(evidence, "/risk/finalRiskScore"));
        addIfPresent(bullets, "Fallback mode", textAt(evidence, "/risk/fallbackMode"));
        addIfPresent(bullets, "Anomaly type", textAt(evidence, "/anomalyTypeAttribution/anomalyType"));
        addIfPresent(bullets, "Anomaly type confidence", textAt(evidence, "/anomalyTypeAttribution/confidence"));
        addIfPresent(bullets, "Selected sequence model", textAt(evidence, "/sequenceEvidence/selectedSequenceModel"));
        addIfPresent(bullets, "Triggered rules", evidence.path("ruleEvidence").path("triggeredRules").toString());
        if (bullets.isEmpty()) {
            bullets.add("Evidence payload is present but contains limited fields");
        }
        return bullets;
    }

    private String possibleInterpretation(JsonNode evidence) {
        String anomalyType = textAt(evidence, "/anomalyTypeAttribution/anomalyType");
        String source = textAt(evidence, "/anomalyTypeAttribution/source");
        if (anomalyType == null) {
            return "The evidence indicates elevated risk, but anomaly type attribution is not available.";
        }
        return "The event may align with " + anomalyType + (source == null ? "." : " according to " + source + ".");
    }

    private List<String> recommendedActions(JsonNode evidence) {
        List<String> actions = new ArrayList<>();
        actions.add("Open the alert investigation and review the stored model, sequence, and rule evidence.");
        if (!evidence.path("ruleEvidence").path("triggeredRules").isMissingNode()
                || !evidence.path("modelScores").isMissingNode()) {
            actions.add("Validate triggered rules and model score contributions against the session timeline.");
        }
        actions.add("Escalate only after analyst verification; this explanation is an aid, not a final determination.");
        return actions;
    }

    private Map<String, Object> modelScoreExplanation(JsonNode evidence) {
        Map<String, Object> map = new LinkedHashMap<>();
        JsonNode scores = evidence.path("modelScores");
        if (scores.isObject()) {
            scores.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().isNumber()
                    ? entry.getValue().numberValue()
                    : entry.getValue().asText()));
        }
        return map;
    }

    private void addIfPresent(List<String> target, String label, String value) {
        if (value != null && !value.isBlank() && !"null".equals(value)) {
            target.add(label + ": " + value);
        }
    }

    private String textAt(JsonNode node, String pointer) {
        if (node == null || pointer == null) {
            return null;
        }
        JsonNode value = node.at(pointer);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void addWarning(V36LlmExplanationResponse response, String warning) {
        if (response.getWarnings() == null) {
            response.setWarnings(new ArrayList<>());
        }
        response.getWarnings().add(warning);
    }
}
