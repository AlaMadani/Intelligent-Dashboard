package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.DeploymentManifest;
import com.noveocare.dataprocessor.ai.ForecastSeriesPoint;
import com.noveocare.dataprocessor.ai.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RedisPubSubProperties;
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
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneOffset;
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
    private final StatisticsService statisticsService;
    private final RedisPubSubProperties pubSubProperties;

    public void cacheSessionInsight(SessionSummary summary, SessionInsight insight) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", summary.getSessionId());
        payload.put("insuredId", summary.getInsuredId());
        payload.put("persona", summary.getPersona());
        payload.put("countryCode", summary.getCountryCode());
        payload.put("city", summary.getCity());
        payload.put("month", summary.getMonth());
        payload.put("sessionNumber", summary.getSessionNumber());
        payload.put("sessionStart", summary.getSessionStart());
        payload.put("sessionEnd", summary.getSessionEnd());
        payload.put("startTime", summary.getSessionStart());
        payload.put("endTime", summary.getSessionEnd());
        payload.put("totalEvents", summary.getTotalEvents());
        payload.put("totalDurationSeconds", summary.getTotalDurationSeconds());
        payload.put("sessionDurationSeconds", summary.getTotalDurationSeconds());
        payload.put("firstAction", summary.getFirstAction());
        payload.put("lastAction", summary.getLastAction());
        payload.put("firstRoute", summary.getFirstRoute());
        payload.put("lastRoute", summary.getLastRoute());
        payload.put("avgInterActionSeconds", summary.getAvgInterActionSeconds());
        payload.put("minInterActionSeconds", summary.getMinInterActionSeconds());
        payload.put("maxInterActionSeconds", summary.getMaxInterActionSeconds());
        payload.put("uniqueActions", summary.getUniqueActions());
        payload.put("uniqueRoutes", summary.getUniqueRoutes());
        payload.put("uniqueIpsUsed", summary.getUniqueIpsUsed());
        payload.put("uniqueDevicesUsed", summary.getUniqueDevicesUsed());
        payload.put("totalKOs", summary.getTotalKOs());
        payload.put("totalOKs", summary.getTotalOKs());
        payload.put("longestKoStreak", summary.getLongestKoStreak());
        payload.put("hasLogin", defaultBoolean(summary.getHasLogin()));
        payload.put("hasLogout", defaultBoolean(summary.getHasLogout()));
        payload.put("ipChanged", defaultBoolean(summary.getIpChanged()));
        payload.put("deviceChanged", defaultBoolean(summary.getDeviceChanged()));
        payload.put("totalDownloadActions", summary.getTotalDownloadActions());
        payload.put("maxDownloadsIn2Minutes", summary.getMaxDownloadsIn2Minutes());
        payload.put("pingPongCount", summary.getPingPongCount());
        payload.put("riskScoreMax", summary.getRiskScoreMax());
        payload.put("riskScoreAvg", summary.getRiskScoreAvg());
        payload.put("anomalyEventCount", summary.getAnomalyEventCount());
        payload.put("anomalyTypes", summary.getAnomalyTypes());
        payload.put("campaignIds", summary.getCampaignIds());
        payload.put("actionCounts", summary.getActionCounts());
        payload.put("actionSequenceSignature", summary.getActionSequenceSignature());
        payload.put("routeSequenceSignature", summary.getRouteSequenceSignature());
        payload.put("binaryAnomaly", insight.isBinaryAnomaly());
        payload.put("anomalyFlag", insight.isAnomaly());
        payload.put("isAnomaly", insight.isAnomaly());
        payload.put("anomalyType", insight.getAnomalyType());
        payload.put("anomalyScore", insight.getAnomalyScore());
        payload.put("anomalyProbability", insight.getAnomalyProbability());
        payload.put("binaryDetectorArtifact", insight.getBinaryDetectorArtifact());
        payload.put("anomalyTypeConfidence", insight.getAnomalyTypeConfidence());
        payload.put("typeConfidence", insight.getAnomalyTypeConfidence());
        payload.put("churnProbability", insight.getChurnProbability());
        payload.put("riskScore", insight.getEnsembleRiskScore());
        payload.put("ensembleRiskScore", insight.getEnsembleRiskScore());
        payload.put("personaCluster", insight.getPersonaCluster());
        payload.put("riskLevel", insight.getRiskLevel());
        payload.put("pathDeviation", insight.getPathDeviation());
        payload.put("pathDeviationFlag", insight.getPathDeviation() != null && insight.getPathDeviation().isDeviated());
        payload.put("transitionProbability", insight.getPathDeviation() == null ? null : insight.getPathDeviation().getTransitionProbability());
        payload.put("transitionFromAction", insight.getPathDeviation() == null ? null : insight.getPathDeviation().getFromAction());
        payload.put("transitionToAction", insight.getPathDeviation() == null ? null : insight.getPathDeviation().getToAction());
        payload.put("rareTransitions", insight.getRareTransitions());
        payload.put("nextActions", insight.getNextActions());
        payload.put("top3NextActions", insight.getNextActions());
        payload.put("contextTags", insight.getContextTags());
        payload.put("triggeredRules", insight.getTriggeredRules());
        payload.put("warnings", insight.getWarnings());
        payload.put("topContributingFeatures", insight.getTopContributingFeatures());
        payload.put("explainabilityText", insight.getExplainabilityText());
        payload.put("actionSequence", summary.getActionSequence());
        payload.put("routeSequence", summary.getRouteSequence());
        payload.put("computedAt", insight.getComputedAt());
        redisCacheService.setJson(
                CacheKeys.sessionInsightKey(summary.getInsuredId(), summary.getSessionId()),
                payload,
                cacheProperties.getSessionInsight());
        String insightKey = CacheKeys.sessionInsightKey(summary.getInsuredId(), summary.getSessionId());
        redisCacheService.addSetMember(CacheKeys.activeSessionInsightsIndexKey(), insightKey);
        redisCacheService.addSetMember(CacheKeys.activeSessionInsightsIndexKey(summary.getInsuredId()), insightKey);
    }

    private boolean defaultBoolean(Integer value) {
        return value != null && value == 1;
    }

    public void removeSessionInsight(String insuredId, String sessionId) {
        String insightKey = CacheKeys.sessionInsightKey(insuredId, sessionId);
        redisCacheService.deleteKey(insightKey);
        redisCacheService.removeSetMember(CacheKeys.activeSessionInsightsIndexKey(), insightKey);
        redisCacheService.removeSetMember(CacheKeys.activeSessionInsightsIndexKey(insuredId), insightKey);
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
                            "step", entry.getKey(),
                            "count", entry.getValue()))
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
                    row.put("from_action", session.getTransitionFromAction());
                    row.put("to_action", session.getTransitionToAction());
                    row.put("transition_probability", session.getTransitionProbability());
                    row.put("session_count", 1); // We don't have aggregation here, but frontend expects it
                    return row;
                })
                .toList();
        if (rows.isEmpty()) {
            rows = runtimeExport("path_deviations.csv");
            if (rows.isEmpty()) {
                rows = runtimeExport("path_summary.csv");
            }
        }
        cacheDashboard("path-deviations", Map.of(
                "items", rows,
                "generatedAt", Instant.now().toString()));
    }

    public void refreshForecasts() {
        cacheDashboard("forecasts", buildForecastSnapshot(LocalDate.now(ZoneOffset.UTC)));
    }

    public Map<String, Object> buildForecastSnapshot(LocalDate referenceDate) {
        LocalDate effectiveDate = referenceDate == null ? LocalDate.now(ZoneOffset.UTC) : referenceDate;
        long totalEventsActual = statisticsService.countEventsForDate(effectiveDate);
        long anomalyEventsActual = statisticsService.countAlertsForDate(effectiveDate);
        long downloadEventsActual = statisticsService.countDownloadsForDate(effectiveDate);

        Map<String, Object> items = new LinkedHashMap<>();
        for (Map.Entry<String, List<ForecastSeriesPoint>> entry : runtimeArtifactService.getForecastSeries().entrySet()) {
            List<ForecastSeriesPoint> alignedPoints = alignForecastPoints(entry.getValue(), effectiveDate.getYear());
            ForecastSeriesPoint baseline = resolveForecastPoint(alignedPoints, effectiveDate);
            DeploymentManifest.ForecastArtifact metadata = runtimeArtifactService.getDeploymentManifest()
                    .getForecasting()
                    .get(entry.getKey());

            long actualCount = actualCountForSeries(entry.getKey(), totalEventsActual, anomalyEventsActual, downloadEventsActual);
            Map<String, Object> seriesPayload = new LinkedHashMap<>();
            seriesPayload.put("seriesKey", entry.getKey());
            seriesPayload.put("label", labelForSeries(entry.getKey()));
            seriesPayload.put("points", alignedPoints);
            seriesPayload.put("baseline", baseline);
            seriesPayload.put("actualCount", actualCount);
            seriesPayload.put("delta", baseline == null || baseline.getYhat() == null ? null : actualCount - baseline.getYhat());
            seriesPayload.put("status", resolveForecastStatus(actualCount, baseline, totalEventsActual > 0));
            seriesPayload.put("mae", metadata == null ? null : metadata.getMae());
            seriesPayload.put("rmse", metadata == null ? null : metadata.getRmse());
            seriesPayload.put("prophet", summarizeProphetModel(runtimeArtifactService.getForecastModelJson().get(entry.getKey())));
            items.put(entry.getKey(), seriesPayload);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referenceDate", effectiveDate);
        payload.put("hasLiveTraffic", totalEventsActual > 0);
        payload.put("generatedAt", Instant.now().toString());
        payload.put("items", items);
        return payload;
    }

    private void cacheDashboard(String view, Object payload) {
        redisCacheService.setJson(CacheKeys.dashboardKey(view), payload, cacheProperties.getDashboard());
        redisCacheService.publishJson(pubSubProperties.getLiveStatsChannel(), Map.of("refresh", view));
    }

    private List<Map<String, Object>> activeSessionInsights() {
        Set<String> keys = redisCacheService.getSetMembers(CacheKeys.activeSessionInsightsIndexKey());
        if (keys.isEmpty()) {
            keys = seedInsightIndex();
        }
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(keys.size());
        for (String key : keys) {
            Map<String, Object> value = redisCacheService.getJson(key, new TypeReference<Map<String, Object>>() { });
            if (value != null) {
                rows.add(value);
                continue;
            }
            removeStaleInsightIndexEntry(key);
        }
        return rows;
    }

    private void removeStaleInsightIndexEntry(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        redisCacheService.removeSetMember(CacheKeys.activeSessionInsightsIndexKey(), key);
        String insuredId = insuredIdFromInsightKey(key);
        if (insuredId != null) {
            redisCacheService.removeSetMember(CacheKeys.activeSessionInsightsIndexKey(insuredId), key);
        }
    }

    private Set<String> seedInsightIndex() {
        Set<String> scannedKeys = redisTemplate.keys(CacheKeys.sessionInsightPattern());
        if (scannedKeys == null || scannedKeys.isEmpty()) {
            return Set.of();
        }
        for (String key : scannedKeys) {
            redisCacheService.addSetMember(CacheKeys.activeSessionInsightsIndexKey(), key);
            String insuredId = insuredIdFromInsightKey(key);
            if (insuredId != null) {
                redisCacheService.addSetMember(CacheKeys.activeSessionInsightsIndexKey(insuredId), key);
            }
        }
        return scannedKeys;
    }

    private String insuredIdFromInsightKey(String key) {
        String prefix = "session:insight:";
        if (key == null || !key.startsWith(prefix)) {
            return null;
        }
        String remainder = key.substring(prefix.length());
        int separator = remainder.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        return remainder.substring(0, separator);
    }

    private Map<String, Object> alertRow(AnomalyEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("insuredId", event.getInsuredId());
        row.put("sessionId", event.getSessionId());
        row.put("eventId", event.getEventId());
        row.put("eventTime", event.getEventTime());
        row.put("anomalyTier", event.getAnomalyTier());
        row.put("anomalyType", event.getAnomalyType());
        row.put("anomalyFlag", event.getAnomalyFlag());
        row.put("anomalyScore", event.getAnomalyScore());
        row.put("anomalyProbability", event.getAnomalyProbability());
        row.put("typeConfidence", event.getTypeConfidence());
        row.put("ruleType", event.getRuleType());
        row.put("churnProbability", event.getChurnProbability());
        row.put("riskScore", event.getRiskScore());
        row.put("personaCluster", event.getPersonaCluster());
        row.put("pathDeviation", event.getPathDeviation());
        row.put("transitionProbability", event.getTransitionProbability());
        row.put("transitionFromAction", event.getTransitionFromAction());
        row.put("transitionToAction", event.getTransitionToAction());
        row.put("modelArtifact", event.getModelArtifact());
        row.put("nextActions", parseJsonValue(event.getNextActionsJson()));
        row.put("eventContext", parseJsonValue(event.getEventJson()));
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
        row.put("typeConfidence", session.getTypeConfidence());
        row.put("churnProbability", session.getChurnProbability());
        row.put("riskScore", session.getEnsembleRiskScore());
        row.put("ensembleRiskScore", session.getEnsembleRiskScore());
        row.put("personaCluster", session.getPersonaCluster());
        row.put("binaryDetectorArtifact", session.getBinaryDetectorArtifact());
        row.put("pathDeviation", session.getPathDeviation());
        row.put("transitionProbability", session.getTransitionProbability());
        row.put("transitionFromAction", session.getTransitionFromAction());
        row.put("transitionToAction", session.getTransitionToAction());
        row.put("rareTransitions", parseJsonValue(session.getRareTransitionsJson()));
        row.put("contextTags", parseJsonValue(session.getContextTagsJson()));
        row.put("topContributingFeatures", parseJsonValue(session.getFeatureContributionsJson()));
        row.put("explainabilityText", session.getExplainabilityText());
        row.put("warnings", parseJsonValue(session.getWarningsJson()));
        row.put("triggeredRules", parseJsonValue(session.getTriggeredRulesJson()));
        row.put("actionSequence", parseJsonValue(session.getActionSequenceJson()));
        row.put("routeSequence", parseJsonValue(session.getRouteSequenceJson()));
        row.put("actionSequenceSignature", session.getActionSequenceSignature());
        row.put("routeSequenceSignature", session.getRouteSequenceSignature());
        row.put("actionCounts", parseJsonValue(session.getActionCountsJson()));
        row.put("sessionStart", session.getStartTime());
        row.put("sessionEnd", session.getEndTime());
        row.put("totalEvents", session.getTotalEvents());
        row.put("sessionDurationSeconds", session.getSessionDurationSeconds());
        row.put("uniqueActions", session.getUniqueActions());
        row.put("uniqueRoutes", session.getUniqueRoutes());
        row.put("uniqueIpsUsed", session.getUniqueIpsUsed());
        row.put("uniqueDevicesUsed", session.getUniqueDevicesUsed());
        row.put("totalKOs", session.getTotalKOs());
        row.put("totalOKs", session.getTotalOKs());
        row.put("longestKoStreak", session.getLongestKoStreak());
        row.put("totalDownloadActions", session.getTotalDownloadActions());
        row.put("maxDownloadsIn2Minutes", session.getMaxDownloadsIn2Minutes());
        row.put("pingPongCount", session.getPingPongCount());
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

    private Object parseJsonValue(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawJson, Object.class);
        } catch (Exception ex) {
            return rawJson;
        }
    }

    private List<ForecastSeriesPoint> alignForecastPoints(List<ForecastSeriesPoint> points, int targetYear) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<ForecastSeriesPoint> aligned = new ArrayList<>(points.size());
        for (ForecastSeriesPoint point : points) {
            LocalDate originalDate = parseDate(point.getDs());
            String alignedDate = originalDate == null
                    ? point.getDs()
                    : originalDate.withYear(targetYear).toString();
            aligned.add(ForecastSeriesPoint.builder()
                    .ds(alignedDate)
                    .yhat(point.getYhat())
                    .yhatLower(point.getYhatLower())
                    .yhatUpper(point.getYhatUpper())
                    .trend(point.getTrend())
                    .build());
        }
        return aligned;
    }

    private ForecastSeriesPoint resolveForecastPoint(List<ForecastSeriesPoint> points, LocalDate referenceDate) {
        if (points == null || points.isEmpty() || referenceDate == null) {
            return null;
        }
        return points.stream()
                .filter(point -> referenceDate.equals(parseDate(point.getDs())))
                .findFirst()
                .orElseGet(() -> points.stream()
                        .filter(point -> {
                            LocalDate date = parseDate(point.getDs());
                            return date != null && MonthDay.from(date).equals(MonthDay.from(referenceDate));
                        })
                        .findFirst()
                        .orElse(points.get(points.size() - 1)));
    }

    private Map<String, Object> summarizeProphetModel(String prophetJson) {
        if (prophetJson == null || prophetJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(prophetJson);
            copyIfPresent(node, summary, "growth");
            copyIfPresent(node, summary, "seasonality_mode");
            copyIfPresent(node, summary, "weekly_seasonality");
            copyIfPresent(node, summary, "yearly_seasonality");
            copyIfPresent(node, summary, "interval_width");
            copyIfPresent(node, summary, "seasonality_prior_scale");
            copyIfPresent(node, summary, "changepoint_prior_scale");
            return summary;
        } catch (Exception ex) {
            return Map.of("raw", prophetJson);
        }
    }

    private void copyIfPresent(com.fasterxml.jackson.databind.JsonNode node, Map<String, Object> target, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.path(field);
        if (!value.isMissingNode() && !value.isNull()) {
            target.put(field, objectMapper.convertValue(value, Object.class));
        }
    }

    private long actualCountForSeries(String seriesKey, long totalEventsActual, long anomalyEventsActual, long downloadEventsActual) {
        return switch (seriesKey) {
            case "anomaly_events" -> anomalyEventsActual;
            case "download_events" -> downloadEventsActual;
            case "total_events" -> totalEventsActual;
            default -> 0L;
        };
    }

    private String labelForSeries(String seriesKey) {
        return switch (seriesKey) {
            case "anomaly_events" -> "Anomaly events";
            case "download_events" -> "Download events";
            case "total_events" -> "Total events";
            default -> seriesKey;
        };
    }

    private String resolveForecastStatus(long actualCount, ForecastSeriesPoint baseline, boolean hasLiveTraffic) {
        if (!hasLiveTraffic && actualCount == 0L) {
            return "NO_ACTIVITY";
        }
        if (baseline == null) {
            return "NO_BASELINE";
        }
        double lower = baseline.getYhatLower() == null ? 0.0 : Math.max(0.0, baseline.getYhatLower());
        double upper = baseline.getYhatUpper() == null ? Double.MAX_VALUE : baseline.getYhatUpper();
        if (actualCount > upper) {
            return "ABOVE_FORECAST";
        }
        if (actualCount < lower) {
            return "BELOW_FORECAST";
        }
        return "WITHIN_BOUNDS";
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }
}
