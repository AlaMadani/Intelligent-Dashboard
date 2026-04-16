package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.DeploymentManifest;
import com.noveocare.dataprocessor.ai.ForecastSeriesPoint;
import com.noveocare.dataprocessor.ai.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.entity.AnomalyEvent;
import com.noveocare.dataprocessor.entity.SessionAnalysis;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.AnomalyEventRepository;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardSnapshotService {

    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final RuntimeArtifactService runtimeArtifactService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void cacheSessionInsight(SessionSummary summary, SessionInsight insight) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", summary.getSessionId());
        payload.put("insuredId", summary.getInsuredId());
        payload.put("persona", summary.getPersona());
        payload.put("countryCode", summary.getCountryCode());
        payload.put("city", summary.getCity());
        payload.put("sessionStart", summary.getSessionStart());
        payload.put("sessionEnd", summary.getSessionEnd());
        payload.put("totalEvents", summary.getTotalEvents());
        payload.put("totalDurationSeconds", summary.getTotalDurationSeconds());
        payload.put("firstAction", summary.getFirstAction());
        payload.put("lastAction", summary.getLastAction());
        payload.put("firstRoute", summary.getFirstRoute());
        payload.put("lastRoute", summary.getLastRoute());
        payload.put("binaryAnomaly", insight.isBinaryAnomaly());
        payload.put("anomalyFlag", insight.isAnomaly());
        payload.put("anomalyType", insight.getAnomalyType());
        payload.put("anomalyScore", insight.getAnomalyScore());
        payload.put("anomalyProbability", insight.getAnomalyProbability());
        payload.put("churnProbability", insight.getChurnProbability());
        payload.put("riskScore", insight.getEnsembleRiskScore());
        payload.put("personaCluster", insight.getPersonaCluster());
        payload.put("riskLevel", insight.getRiskLevel());
        payload.put("pathDeviation", insight.getPathDeviation());
        payload.put("nextActions", insight.getNextActions());
        payload.put("triggeredRules", insight.getTriggeredRules());
        payload.put("warnings", insight.getWarnings());
        payload.put("computedAt", insight.getComputedAt());
        redisCacheService.setJson(
                CacheKeys.sessionInsightKey(summary.getInsuredId(), summary.getSessionId()),
                payload,
                cacheProperties.getSessionInsight());
    }

    public void removeSessionInsight(String insuredId, String sessionId) {
        redisCacheService.deleteKey(CacheKeys.sessionInsightKey(insuredId, sessionId));
    }

    public void refreshAll() {
        refreshAlertsFeed();
        refreshRiskySessions();
        refreshClusterMix();
        refreshDropOffs();
        refreshPathDeviations();
        refreshForecasts();
    }

    public void refreshAlertsFeed() {
        List<Map<String, Object>> alerts = anomalyEventRepository.findTop100ByOrderByDetectedAtDesc().stream()
                .map(this::alertRow)
                .toList();
        if (alerts.isEmpty()) {
            alerts = runtimeExport("alerts_feed.csv");
        }
        cacheDashboard("alerts", Map.of(
                "items", alerts,
                "generatedAt", Instant.now().toString()));
    }

    public void refreshRiskySessions() {
        List<Map<String, Object>> rows = new ArrayList<>(activeSessionInsights());
        sessionAnalysisRepository.findTop20ByOrderByEnsembleRiskScoreDescCreatedAtDesc().stream()
                .map(this::sessionRow)
                .forEach(rows::add);
        rows = rows.stream()
                .sorted(Comparator.comparing((Map<String, Object> row) -> numeric(row.get("riskScore"))).reversed())
                .limit(20)
                .toList();
        if (rows.isEmpty()) {
            rows = runtimeExport("top_risky_sessions.csv");
        }
        cacheDashboard("risky-sessions", Map.of(
                "items", rows,
                "generatedAt", Instant.now().toString()));
    }

    public void refreshClusterMix() {
        List<SessionAnalysis> sessions = sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc();
        List<Map<String, Object>> rows;
        if (sessions.isEmpty()) {
            rows = runtimeExport("cluster_mix.csv");
        } else {
            Map<Integer, Long> counts = new LinkedHashMap<>();
            for (SessionAnalysis session : sessions) {
                if (session.getPersonaCluster() == null) {
                    continue;
                }
                counts.put(session.getPersonaCluster(), counts.getOrDefault(session.getPersonaCluster(), 0L) + 1);
            }
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            rows = counts.entrySet().stream()
                    .map(entry -> Map.<String, Object>of(
                            "cluster_id", entry.getKey(),
                            "traffic_share", total == 0 ? 0.0 : (double) entry.getValue() / total))
                    .toList();
        }
        cacheDashboard("cluster-mix", Map.of(
                "items", rows,
                "generatedAt", Instant.now().toString()));
    }

    public void refreshDropOffs() {
        List<SessionAnalysis> sessions = sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc();
        List<Map<String, Object>> rows;
        if (sessions.isEmpty()) {
            rows = runtimeExport("dropoff_actions.csv");
        } else {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (SessionAnalysis session : sessions) {
                if (!Boolean.TRUE.equals(session.getEndedAbruptly()) || session.getLastAction() == null) {
                    continue;
                }
                counts.put(session.getLastAction(), counts.getOrDefault(session.getLastAction(), 0L) + 1);
            }
            rows = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(entry -> Map.<String, Object>of(
                            "lastAction", entry.getKey(),
                            "abrupt_session_count", entry.getValue()))
                    .toList();
        }
        cacheDashboard("drop-offs", Map.of(
                "items", rows,
                "generatedAt", Instant.now().toString()));
    }

    public void refreshPathDeviations() {
        List<Map<String, Object>> rows = sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .filter(session -> Boolean.TRUE.equals(session.getPathDeviation()))
                .map(session -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", session.getSessionId());
                    row.put("insuredId", session.getInsuredId());
                    row.put("current_action", session.getTransitionFromAction());
                    row.put("actual_next_action", session.getTransitionToAction());
                    row.put("train_probability", session.getTransitionProbability());
                    return row;
                })
                .toList();
        if (rows.isEmpty()) {
            rows = runtimeExport("path_deviations.csv");
        }
        cacheDashboard("path-deviations", Map.of(
                "items", rows,
                "generatedAt", Instant.now().toString()));
    }

    public void refreshForecasts() {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, List<ForecastSeriesPoint>> entry : runtimeArtifactService.getForecastSeries().entrySet()) {
            DeploymentManifest.ForecastArtifact metadata = runtimeArtifactService.getDeploymentManifest().getForecasting().get(entry.getKey());
            Map<String, Object> seriesPayload = new LinkedHashMap<>();
            seriesPayload.put("points", entry.getValue());
            seriesPayload.put("mae", metadata == null ? null : metadata.getMae());
            seriesPayload.put("rmse", metadata == null ? null : metadata.getRmse());
            seriesPayload.put("prophetJson", runtimeArtifactService.getForecastModelJson().get(entry.getKey()));
            payload.put(entry.getKey(), seriesPayload);
        }
        cacheDashboard("forecasts", Map.of(
                "items", payload,
                "generatedAt", Instant.now().toString()));
    }

    private void cacheDashboard(String view, Object payload) {
        redisCacheService.setJson(CacheKeys.dashboardKey(view), payload, cacheProperties.getDashboard());
    }

    private List<Map<String, Object>> activeSessionInsights() {
        Set<String> keys = redisTemplate.keys("session:insight:*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(keys.size());
        for (String key : keys) {
            Map<String, Object> value = redisCacheService.getJson(key, new TypeReference<Map<String, Object>>() { });
            if (value != null) {
                rows.add(value);
            }
        }
        return rows;
    }

    private Map<String, Object> alertRow(AnomalyEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("insuredId", event.getInsuredId());
        row.put("sessionId", event.getSessionId());
        row.put("anomalyType", event.getAnomalyType());
        row.put("anomalyFlag", event.getAnomalyFlag());
        row.put("anomalyScore", event.getAnomalyScore());
        row.put("anomalyProbability", event.getAnomalyProbability());
        row.put("riskScore", event.getRiskScore());
        row.put("personaCluster", event.getPersonaCluster());
        row.put("pathDeviation", event.getPathDeviation());
        row.put("detectedAt", event.getDetectedAt());
        return row;
    }

    private Map<String, Object> sessionRow(SessionAnalysis session) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", session.getSessionId());
        row.put("insuredId", session.getInsuredId());
        row.put("persona", session.getPersona());
        row.put("countryCode", session.getCountryCode());
        row.put("anomalyFlag", session.getIsAnomaly());
        row.put("anomalyType", session.getAnomalyType());
        row.put("anomalyScore", session.getIsoScore());
        row.put("anomalyProbability", session.getAnomalyProbability());
        row.put("churnProbability", session.getChurnProbability());
        row.put("riskScore", session.getEnsembleRiskScore());
        row.put("personaCluster", session.getPersonaCluster());
        row.put("pathDeviation", session.getPathDeviation());
        row.put("sessionStart", session.getStartTime());
        row.put("sessionEnd", session.getEndTime());
        row.put("totalEvents", session.getTotalEvents());
        row.put("lastAction", session.getLastAction());
        return row;
    }

    private List<Map<String, Object>> runtimeExport(String name) {
        List<Map<String, String>> rows = runtimeArtifactService.getDashboardExports().get(name);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> converted = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            converted.add(new LinkedHashMap<>(row));
        }
        return converted;
    }

    private double numeric(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return objectMapper.convertValue(value, Double.class);
        } catch (IllegalArgumentException ex) {
            return 0.0;
        }
    }
}
