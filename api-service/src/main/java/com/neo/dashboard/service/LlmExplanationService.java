package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.neo.dashboard.dto.v36.V36LlmExplanationRequest;
import com.neo.dashboard.dto.v36.V36LlmExplanationResponse;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.redis.CacheKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LlmExplanationService {

    private static final String DISCLAIMER = "Generated from model and rule evidence. Analyst aid only.";

    private final LlmEvidenceReadService evidenceReadService;
    private final LlmPromptBuilderV36 promptBuilder;
    private final LlmExplanationCacheService cacheService;
    private final GeminiClient geminiClient;

    @Value("${app.v36.explanations.default-style:security_analyst}")
    private String defaultStyle;

    @Value("${app.v36.explanations.default-language:en}")
    private String defaultLanguage;

    @Value("${app.v36.explanations.enabled:true}")
    private boolean explanationsEnabled;

    public Optional<V36LlmExplanationResponse> getCached(String eventId) {
        return cacheService.getLatest(eventId);
    }

    public V36LlmExplanationResponse generate(String eventId, V36LlmExplanationRequest request) {
        boolean forceRefresh = request != null && Boolean.TRUE.equals(request.getForceRefresh());
        String style = safe(request == null ? null : request.getStyle(), defaultStyle);
        String language = safe(request == null ? null : request.getLanguage(), defaultLanguage);
        boolean includeActions = request == null || !Boolean.FALSE.equals(request.getIncludeRecommendedActions());

        JsonNode evidence = evidenceReadService.readEvidence(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND",
                        "V3.6.1 LLM evidence payload not found for event " + eventId));
        if (!evidenceReadService.isV36Evidence(evidence)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE_SCHEMA", "LLM evidence payload is not schemaVersion v3.6.1");
        }

        String evidenceHash = evidenceReadService.evidenceHash(evidence);
        if (!forceRefresh) {
            Optional<V36LlmExplanationResponse> cached = cacheService.get(eventId, evidenceHash, style, language);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        String prompt = promptBuilder.buildPrompt(evidence, style, language, includeActions);
        Optional<String> generated = explanationsEnabled ? geminiClient.generate(eventId, prompt) : Optional.empty();
        V36LlmExplanationResponse response = generated
                .map(text -> fromGeneratedText(eventId, evidenceHash, style, language, text, evidence))
                .orElseGet(() -> deterministicFallback(eventId, evidenceHash, style, language, evidence));

        cacheService.put(response);
        return response;
    }

    private V36LlmExplanationResponse fromGeneratedText(String eventId,
                                                        String evidenceHash,
                                                        String style,
                                                        String language,
                                                        String generatedText,
                                                        JsonNode evidence) {
        return new V36LlmExplanationResponse(
                CacheKeys.V36_SCHEMA_VERSION,
                eventId,
                Instant.now(),
                "gemini",
                geminiClient.getModel(),
                false,
                evidenceHash,
                style,
                language,
                generatedText.trim(),
                evidenceBullets(evidence),
                possibleInterpretation(evidence),
                recommendedActions(evidence),
                modelScoreExplanation(evidence),
                DISCLAIMER,
                false
        );
    }

    private V36LlmExplanationResponse deterministicFallback(String eventId,
                                                           String evidenceHash,
                                                           String style,
                                                           String language,
                                                           JsonNode evidence) {
        String riskLevel = textAt(evidence, "/risk/riskLevel");
        String finalRiskScore = textAt(evidence, "/risk/finalRiskScore");
        String anomalyType = textAt(evidence, "/anomalyTypeAttribution/anomalyType");
        String summary = "LLM provider unavailable. The event is flagged from V3.6.1 evidence"
                + (riskLevel == null ? "" : " with risk level " + riskLevel)
                + (finalRiskScore == null ? "" : " and final risk score " + finalRiskScore)
                + (anomalyType == null ? "." : " for anomaly type " + anomalyType + ".");

        return new V36LlmExplanationResponse(
                CacheKeys.V36_SCHEMA_VERSION,
                eventId,
                Instant.now(),
                "heuristic",
                "local-fallback",
                false,
                evidenceHash,
                style,
                language,
                summary,
                evidenceBullets(evidence),
                possibleInterpretation(evidence),
                recommendedActions(evidence),
                modelScoreExplanation(evidence),
                DISCLAIMER,
                true
        );
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
}
