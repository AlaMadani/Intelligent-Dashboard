package com.neo.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36PersonaDisabledDto;
import com.neo.dashboard.dto.v36.V36User360Response;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class V36User360Service {

    private final V36RedisReadService redisReadService;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final ObjectMapper objectMapper;

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

        List<Map<String, Object>> items = sessions.stream()
                .filter(session -> session.getChurnProbability() != null)
                .sorted(Comparator.comparing(SessionAnalysis::getChurnProbability, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(normalizeLimit(limit))
                .map(session -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("schemaVersion", CacheKeys.V36_SCHEMA_VERSION);
                    item.put("insuredId", session.getInsuredId());
                    item.put("sessionId", session.getSessionId());
                    item.put("churnProbability", session.getChurnProbability());
                    item.put("churnRiskLevel", session.getChurnRiskLevel());
                    item.put("finalRiskScore", session.getFinalRiskScore());
                    item.put("riskLevel", session.getRiskLevel());
                    return item;
                })
                .toList();
        return ApiPageResponse.of(items, normalizeLimit(limit), 0, false);
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
                "riskLevel", text(node, "churnRiskLevel", "UNKNOWN")
        ));
        response.setRisk(Map.of(
                "averageRiskScoreLast30d", numeric(node, "averageRiskScoreLast30d"),
                "alertCountLast30d", node.path("alertCountLast30d").asInt(0),
                "criticalAlertCountLast30d", node.path("criticalAlertCountLast30d").asInt(0)
        ));
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("usualCountry", text(node, "usualCountry", null));
        baseline.put("usualDevice", text(node, "usualDevice", null));
        baseline.put("usualBrowser", text(node, "usualBrowser", null));
        baseline.put("usualActiveHours", safeJsonToValue(node, "usualActiveHours"));
        baseline.put("topApiFamilies", safeJsonToValue(node, "topApiFamilies"));
        response.setBaseline(baseline);
        response.setRecentSessions(toListOfMaps(node.path("recentSessions")));
        response.setRiskTimeline(toListOfMaps(node.path("riskTimeline")));
        return response;
    }

    private V36User360Response buildFallback(String insuredId) {
        List<SessionAnalysis> recentSessions = sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc(insuredId);
        List<AnomalyEvent> anomalies = anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc(insuredId);

        V36User360Response response = new V36User360Response();
        response.setInsuredId(insuredId);
        response.setPersona(new V36PersonaDisabledDto(false, -1, "persona_disabled", "disabled_v3_6_refactor", null));
        response.setChurn(Map.of(
                "probability", recentSessions.stream()
                        .map(SessionAnalysis::getChurnProbability)
                        .filter(value -> value != null)
                        .findFirst()
                        .orElse(0.0),
                "riskLevel", recentSessions.stream()
                        .map(SessionAnalysis::getChurnRiskLevel)
                        .filter(this::hasText)
                        .findFirst()
                        .orElse("UNKNOWN")
        ));
        response.setRisk(Map.of(
                "averageRiskScoreLast30d", recentSessions.stream()
                        .mapToDouble(s -> nullSafe(s.getFinalRiskScore()))
                        .average()
                        .orElse(0.0),
                "alertCountLast30d", (int) anomalies.stream()
                        .filter(a -> a.getAnomalyFlag() != null && a.getAnomalyFlag())
                        .count(),
                "criticalAlertCountLast30d", (int) anomalies.stream()
                        .filter(a -> "CRITICAL".equalsIgnoreCase(a.getAnomalyTier()))
                        .count()
        ));
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("usualCountry", recentSessions.stream().map(SessionAnalysis::getCountryCode).filter(this::hasText).findFirst().orElse(null));
        baseline.put("usualDevice", null);
        baseline.put("usualBrowser", null);
        baseline.put("usualActiveHours", "n/a");
        baseline.put("topApiFamilies", List.of());
        response.setBaseline(baseline);
        response.setRecentSessions(recentSessions.stream()
                .limit(10)
                .map(session -> objectMapper.convertValue(session, new TypeReference<Map<String, Object>>() { }))
                .toList());
        response.setRiskTimeline(anomalies.stream()
                .limit(10)
                .map(anomaly -> {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("timestamp", anomaly.getEventTime() != null ? anomaly.getEventTime().toString() : null);
                    point.put("finalRiskScore", anomaly.getFinalRiskScore());
                    point.put("riskLevel", anomaly.getAnomalyTier());
                    point.put("eventId", anomaly.getEventId());
                    return point;
                })
                .toList());
        return response;
    }

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

    private double nullSafe(Double value) {
        return value == null ? 0.0 : value;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 50;
        }
        return Math.min(limit, 200);
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
}
