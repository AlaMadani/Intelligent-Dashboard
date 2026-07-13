package com.neo.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto;
import com.neo.dashboard.dto.v36.V36PersonaDisabledDto;
import com.neo.dashboard.dto.v36.V36User360Response;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class V36User360Service {

    private final V36RedisReadService redisReadService;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final ObjectMapper objectMapper;
    private final V36NextEventPredictionService nextEventPredictionService;

    public V36User360Response getUser360(String insuredId) {
        Optional<JsonNode> redis = redisReadService.readJson(CacheKeys.user360Key(insuredId));
        if (redis.isPresent()) {
            return normalizeRedisUser360(redis.get(), insuredId);
        }
        return buildFallback(insuredId);
    }

    @Transactional(readOnly = true)
    public ApiPageResponse<Map<String, Object>> getChurnUsers(String riskLevel, int limit) {
        List<SessionAnalysis> sessions = hasText(riskLevel)
                ? sessionAnalysisRepository.findTop50ByChurnRiskLevelOrderByCreatedAtDesc(riskLevel.toUpperCase())
                : sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc();

        int maxLimit = normalizeLimit(limit);

        List<Map<String, Object>> items = sessions.stream()
                .filter(session -> session.getChurnProbability() != null)
                .collect(Collectors.groupingBy(
                        SessionAnalysis::getInsuredId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), this::pickWinner)
                ))
                .values().stream()
                .sorted(Comparator.comparing(SessionAnalysis::getChurnProbability, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(maxLimit)
                .map(session -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("schemaVersion", CacheKeys.V36_SCHEMA_VERSION);
                    item.put("insuredId", session.getInsuredId());
                    item.put("sessionId", session.getSessionId());
                    item.put("churnProbability", session.getChurnProbability());
                    item.put("churnRiskLevel", session.getChurnRiskLevel());
                    item.put("latestFinalRiskScore", session.getFinalRiskScore());
                    item.put("latestRiskLevel", session.getRiskLevel());

                    Map<String, Object> riskSummary = computeUserRiskSummaryLast30d(session.getInsuredId());
                    item.put("averageRiskScoreLast30d", riskSummary.get("averageRiskScoreLast30d"));
                    item.put("alertCountLast30d", riskSummary.get("alertCountLast30d"));
                    item.put("criticalAlertCountLast30d", riskSummary.get("criticalAlertCountLast30d"));

                    item.put("source", "sql_fallback");
                    return item;
                })
                .toList();

        return ApiPageResponse.of(items, maxLimit, 0, false);
    }

    private V36User360Response normalizeRedisUser360(JsonNode node, String insuredId) {
        V36User360Response response = new V36User360Response();
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        response.setInsuredId(text(node, "insuredId", insuredId));
        response.setPersona(new V36PersonaDisabledDto(
                node.path("personaEnabled").asBoolean(false),
                node.path("personaCluster").isNumber() ? node.path("personaCluster").asInt() : -1,
                text(node, "personaLabel", "persona_disabled"),
                text(node, "personaSource", "disabled_v3_6_refactor"),
                node.path("personaConfidence").isNumber() ? node.path("personaConfidence").asDouble() : null
        ));
        response.setChurn(Map.of(
                "probability", numeric(node, "churnProbability"),
                "riskLevel", text(node, "churnRiskLevel", "UNKNOWN"),
                "source", "redis"
        ));
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("averageRiskScoreLast30d", numeric(node, "averageRiskScoreLast30d"));
        risk.put("alertCountLast30d", node.path("alertCountLast30d").asInt(0));
        risk.put("criticalAlertCountLast30d", node.path("criticalAlertCountLast30d").asInt(0));
        risk.put("source", "redis");
        response.setRisk(risk);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("usualCountry", text(node, "usualCountry", null));
        baseline.put("usualDevice", text(node, "usualDevice", null));
        baseline.put("usualBrowser", text(node, "usualBrowser", null));
        baseline.put("usualActiveHours", safeJsonToValue(node, "usualActiveHours"));
        baseline.put("topApiFamilies", safeJsonToValue(node, "topApiFamilies"));
        response.setBaseline(baseline);
        response.setRecentSessions(toListOfMaps(node.path("recentSessions")));
        response.setRiskTimeline(toListOfMaps(node.path("riskTimeline")));
        String firstSessionId = extractFirstSessionId(node.path("recentSessions"));
        enrichNextEventPrediction(response, insuredId, firstSessionId);
        return response;
    }

    private String extractFirstSessionId(JsonNode recentSessions) {
        if (recentSessions != null && recentSessions.isArray() && recentSessions.size() > 0) {
            JsonNode first = recentSessions.get(0);
            if (first != null && first.has("sessionId")) {
                return first.get("sessionId").asText();
            }
        }
        return null;
    }

    private void enrichNextEventPrediction(V36User360Response response, String insuredId, String sessionId) {
        try {
            V36NextEventPredictionDto prediction = nextEventPredictionService.getBestForUser360(insuredId, sessionId);
            response.setNextEventPrediction(objectMapper.convertValue(prediction, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            log.warn("Failed to enrich nextEventPrediction for insuredId={}", insuredId, e);
        }
    }

    private V36User360Response buildFallback(String insuredId) {
        List<SessionAnalysis> recentSessions = sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc(insuredId);
        List<AnomalyEvent> anomalies = anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc(insuredId);

        V36User360Response response = new V36User360Response();
        response.setInsuredId(insuredId);
        response.setPersona(new V36PersonaDisabledDto(false, -1, "persona_disabled", "disabled_v3_6_refactor", null));
        Map<String, Object> churn = new LinkedHashMap<>();
        churn.put("probability", recentSessions.stream()
                .map(SessionAnalysis::getChurnProbability)
                .filter(value -> value != null)
                .findFirst()
                .orElse(0.0));
        churn.put("riskLevel", recentSessions.stream()
                .map(SessionAnalysis::getChurnRiskLevel)
                .filter(this::hasText)
                .findFirst()
                .orElse("UNKNOWN"));
        churn.put("source", "sql_fallback");
        churn.put("warning", "SQL historical rows may contain pre-correction data; TCN scores before Level-E may be inflated");
        response.setChurn(churn);
        Map<String, Object> risk = buildRiskSummaryFromData(recentSessions, anomalies);
        risk.put("source", "sql_fallback");
        risk.put("warning", "SQL historical rows may contain pre-correction data; TCN scores before Level-E may be inflated");
        response.setRisk(risk);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("usualCountry", recentSessions.stream().map(SessionAnalysis::getCountryCode).filter(this::hasText).findFirst().orElse(null));
        baseline.put("usualDevice", extractDeviceFromAnomalies(anomalies));
        baseline.put("usualBrowser", extractBrowserFromAnomalies(anomalies));
        baseline.put("usualActiveHours", computeUsualActiveHours(recentSessions, anomalies));
        baseline.put("topApiFamilies", computeTopApiFamilies(anomalies, recentSessions));
        baseline.put("source", "sql_fallback");
        response.setBaseline(baseline);
        response.setRecentSessions(recentSessions.stream()
                .limit(10)
                .map(session -> {
                    Map<String, Object> item = objectMapper.convertValue(session, new TypeReference<Map<String, Object>>() { });
                    item.put("source", "sql_fallback");
                    return item;
                })
                .toList());
        response.setRiskTimeline(anomalies.stream()
                .limit(10)
                .map(anomaly -> {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("timestamp", anomaly.getEventTime() != null ? anomaly.getEventTime().toString() : null);
                    point.put("finalRiskScore", anomaly.getFinalRiskScore());
                    point.put("riskLevel", deriveRiskLevel(anomaly));
                    point.put("pointType", anomaly.getAnomalyTier());
                    point.put("eventId", anomaly.getEventId());
                    point.put("source", "sql_fallback");
                    point.put("warning", "SQL historical rows may contain pre-correction data");
                    return point;
                })
                .toList());
        String firstSessionId = recentSessions.isEmpty() ? null : recentSessions.get(0).getSessionId();
        enrichNextEventPrediction(response, insuredId, firstSessionId);
        return response;
    }

    private Map<String, Object> buildRiskSummaryFromData(List<SessionAnalysis> recentSessions, List<AnomalyEvent> anomalies) {
        Map<String, Object> risk = new LinkedHashMap<>();
        Double avg = recentSessions.stream()
                .map(SessionAnalysis::getFinalRiskScore)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(Double.NaN);
        risk.put("averageRiskScoreLast30d", Double.isNaN(avg) ? null : avg);
        risk.put("alertCountLast30d", (int) anomalies.stream()
                .filter(a -> a.getAnomalyFlag() != null && a.getAnomalyFlag())
                .count());
        risk.put("criticalAlertCountLast30d", (int) anomalies.stream()
                .filter(a -> "CRITICAL".equalsIgnoreCase(a.getAnomalyTier()))
                .count());
        return risk;
    }

    /* ------------------------------------------------------------------ */
    /*  Baseline enrichment helpers                                        */
    /* ------------------------------------------------------------------ */

    private List<Integer> computeUsualActiveHours(List<SessionAnalysis> sessions, List<AnomalyEvent> anomalies) {
        Map<Integer, Long> hourCounts = new LinkedHashMap<>();
        for (SessionAnalysis s : sessions) {
            addHour(hourCounts, s.getStartTime());
            addHour(hourCounts, s.getEndTime());
        }
        for (AnomalyEvent a : anomalies) {
            addHour(hourCounts, a.getEventTime());
        }
        if (hourCounts.isEmpty()) return List.of();
        return hourCounts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void addHour(Map<Integer, Long> counts, Instant timestamp) {
        if (timestamp == null) return;
        int hour = timestamp.atZone(ZoneOffset.UTC).getHour();
        counts.merge(hour, 1L, Long::sum);
    }

    private List<String> computeTopApiFamilies(List<AnomalyEvent> anomalies, List<SessionAnalysis> sessions) {
        Map<String, Long> familyCounts = new LinkedHashMap<>();
        for (AnomalyEvent a : anomalies) {
            addApiFamily(familyCounts, extractApiFamilyFromAnomaly(a));
        }
        for (SessionAnalysis s : sessions) {
            addApiFamily(familyCounts, extractApiFamilyFromSession(s));
            for (String routeFamily : extractRouteFamilies(s)) {
                addApiFamily(familyCounts, routeFamily);
            }
        }
        if (familyCounts.isEmpty()) return List.of();
        return familyCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void addApiFamily(Map<String, Long> counts, String family) {
        if (family == null) return;
        counts.merge(family, 1L, Long::sum);
    }

    private String normalizeApiFamily(String value) {
        if (!hasText(value)) return null;
        String v = value.trim().toLowerCase();
        if (v.isBlank() || "unknown".equals(v) || "n/a".equals(v)) return null;
        if (v.startsWith("/")) {
            String[] parts = v.split("/");
            if (parts.length >= 2) return parts[1];
        }
        return v;
    }

    private String extractApiFamilyFromAnomaly(AnomalyEvent a) {
        String fromJson = extractApiFamilyFromJson(a.getEventJson(), "apiFamily");
        if (fromJson != null) return normalizeApiFamily(fromJson);

        String fromTemplate = extractApiFamilyFromJson(a.getEventJson(), "apiTemplate");
        if (fromTemplate != null) return normalizeApiFamily(fromTemplate);

        String fromInv = extractApiFamilyFromEventMetadata(a.getInvestigationPayloadJson());
        if (fromInv != null) return normalizeApiFamily(fromInv);

        String fromLlm = extractApiFamilyFromEventMetadata(a.getLlmExplanationEvidencePayloadJson());
        if (fromLlm != null) return normalizeApiFamily(fromLlm);

        return null;
    }

    private String extractApiFamilyFromSession(SessionAnalysis s) {
        String fromLlm = extractApiFamilyFromEventMetadata(s.getLlmExplanationEvidencePayloadJson());
        if (fromLlm != null) return normalizeApiFamily(fromLlm);

        String fromInv = extractApiFamilyFromEventMetadata(s.getInvestigationPayloadJson());
        if (fromInv != null) return normalizeApiFamily(fromInv);

        String fromEvidence = extractApiFamilyFromJson(s.getAnomalyTypeEvidenceJson(), "apiFamily");
        if (fromEvidence != null) return normalizeApiFamily(fromEvidence);

        String fromEvidenceTemplate = extractApiFamilyFromJson(s.getAnomalyTypeEvidenceJson(), "apiTemplate");
        if (fromEvidenceTemplate != null) return normalizeApiFamily(fromEvidenceTemplate);

        return null;
    }

    private String extractApiFamilyFromEventMetadata(String jsonPayload) {
        if (!hasText(jsonPayload)) return null;
        JsonNode root = parseJson(jsonPayload);
        if (root == null) return null;
        JsonNode meta = root.path("eventMetadata");
        if (meta.isObject()) {
            String family = textAt(meta, "apiFamily");
            if (family != null) return family;
            String template = textAt(meta, "apiTemplate");
            if (template != null) return template;
        }
        JsonNode attribution = root.path("anomalyTypeAttribution");
        if (attribution.isObject()) {
            JsonNode ev = attribution.path("evidence");
            if (ev.isObject()) {
                String family = textAt(ev, "apiFamily");
                if (family != null) return family;
                String template = textAt(ev, "apiTemplate");
                if (template != null) return template;
            }
        }
        return null;
    }

    private String extractApiFamilyFromJson(String json, String field) {
        if (!hasText(json)) return null;
        JsonNode node = parseJson(json);
        if (node == null) return null;
        return textAt(node, field);
    }

    private static final Map<String, String> ROUTE_TO_FAMILY = Map.of(
            "login", "auth",
            "logout", "auth",
            "documents", "documents",
            "refunds", "insured",
            "home", "home",
            "bankinginformation", "bankinginformation",
            "address", "insured",
            "file", "documents"
    );

    private List<String> extractRouteFamilies(SessionAnalysis s) {
        if (!hasText(s.getRouteSequenceJson())) return List.of();
        try {
            List<String> routes = objectMapper.readValue(s.getRouteSequenceJson(), new TypeReference<List<String>>() { });
            return routes.stream()
                    .map(r -> ROUTE_TO_FAMILY.get(r.trim().toLowerCase()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractDeviceFromAnomalies(List<AnomalyEvent> anomalies) {
        for (AnomalyEvent a : anomalies) {
            if (hasText(a.getEventJson())) {
                JsonNode node = parseJson(a.getEventJson());
                if (node != null) {
                    String device = textAt(node, "device");
                    if (device != null) return device;
                }
            }
        }
        return null;
    }

    private String extractBrowserFromAnomalies(List<AnomalyEvent> anomalies) {
        for (AnomalyEvent a : anomalies) {
            if (hasText(a.getEventJson())) {
                JsonNode node = parseJson(a.getEventJson());
                if (node != null) {
                    String browser = textAt(node, "browser");
                    if (browser != null) return browser;
                }
            }
        }
        return null;
    }

    private JsonNode parseJson(String json) {
        if (!hasText(json)) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String textAt(JsonNode node, String field) {
        return node.path(field).isValueNode() ? node.path(field).asText() : null;
    }

    /* ------------------------------------------------------------------ */

    private List<Map<String, Object>> toListOfMaps(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        try {
            return objectMapper.treeToValue(node, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(null);
        return hasText(value) ? value : fallback;
    }

    private double numeric(JsonNode node, String field) {
        return node.path(field).isNumber() ? node.path(field).asDouble() : 0.0;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 50;
        }
        return Math.min(limit, 200);
    }

    private String deriveRiskLevel(AnomalyEvent anomaly) {
        if (anomaly == null) return null;
        if (hasText(anomaly.getRiskLevel())) {
            String rl = anomaly.getRiskLevel().toUpperCase();
            if (Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(rl)) {
                return rl;
            }
        }
        Double score = anomaly.getFinalRiskScore();
        if (score == null) return null;
        if (score >= 80.0) return "CRITICAL";
        if (score >= 60.0) return "HIGH";
        if (score >= 35.0) return "MEDIUM";
        return "LOW";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Object safeJsonToValue(JsonNode node, String field) {
        if (!node.has(field) || node.path(field).isNull() || node.path(field).isMissingNode()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node.get(field), Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Picks the winning session from a group of sessions for the same insuredId.
     * Winner rule: latest endTime, then latest createdAt, then highest churnProbability.
     */
    SessionAnalysis pickWinner(List<SessionAnalysis> sessions) {
        return sessions.stream()
                .min((a, b) -> {
                    int cmp = compareDesc(a.getEndTime(), b.getEndTime());
                    if (cmp != 0) return cmp;
                    cmp = compareDesc(a.getCreatedAt(), b.getCreatedAt());
                    if (cmp != 0) return cmp;
                    return compareDesc(a.getChurnProbability(), b.getChurnProbability());
                })
                .orElseThrow(() -> new IllegalStateException("Empty session group"));
    }

    private <T extends Comparable<T>> int compareDesc(T a, T b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return b.compareTo(a);
    }

    /**
     * Computes 30-day security risk summary for a given insuredId.
     * Same logic as User360 SQL fallback. Queries DB for the risk data.
     */
    public Map<String, Object> computeUserRiskSummaryLast30d(String insuredId) {
        List<SessionAnalysis> recentSessions = sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc(insuredId);
        List<AnomalyEvent> anomalies = anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc(insuredId);
        return buildRiskSummaryFromData(recentSessions, anomalies);
    }
}
