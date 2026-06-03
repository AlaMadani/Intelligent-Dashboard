package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.forecast.ForecastPrediction;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RedisPubSubProperties;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.entity.AnomalyEvent;
import com.noveocare.dataprocessor.entity.SessionAnalysis;
import com.noveocare.dataprocessor.inference.ModelHealthService;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.AnomalyEventRepository;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
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
    private final ForecastRuntimeService forecastRuntimeService;
    private final ModelHealthService modelHealthService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final StatisticsService statisticsService;
    private final RedisPubSubProperties pubSubProperties;

    public void cacheSessionInsight(SessionSummary summary, SessionInsight insight) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
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
        payload.put("riskScore", insight.getFinalRiskScore() == null ? insight.getEnsembleRiskScore() : insight.getFinalRiskScore());
        payload.put("ensembleRiskScore", insight.getFinalRiskScore() == null ? insight.getEnsembleRiskScore() : insight.getFinalRiskScore());
        payload.put("personaCluster", insight.getPersonaCluster());
        payload.put("personaLabel", insight.getPersonaLabel());
        payload.put("personaSource", insight.getPersonaSource());
        payload.put("personaConfidence", insight.getPersonaConfidence());
        payload.put("riskLevel", insight.getRiskLevel());
        payload.put("pathDeviation", insight.getPathDeviation());
        payload.put("pathDeviationFlag", insight.getPathDeviation() != null && insight.getPathDeviation().isDeviated());
        payload.put("transitionProbability", insight.getPathDeviation() == null ? null : insight.getPathDeviation().getTransitionProbability());
        payload.put("transitionFromAction", insight.getPathDeviation() == null ? null : insight.getPathDeviation().getFromAction());
        payload.put("transitionToAction", insight.getPathDeviation() == null ? null : insight.getPathDeviation().getToAction());
        payload.put("rareTransitions", insight.getRareTransitions());
        payload.put("nextActions", insight.getNextActions() == null ? List.of() : insight.getNextActions());
        payload.put("top3NextActions", insight.getNextActions() == null ? List.of() : insight.getNextActions());
        payload.put("contextTags", insight.getContextTags());
        payload.put("triggeredRules", insight.getTriggeredRules());
        payload.put("warnings", insight.getWarnings());
        payload.put("topContributingFeatures", insight.getTopContributingFeatures());
        payload.put("sequenceModelPrimary", insight.getSequenceModelPrimary());
        payload.put("sequenceModelFast", insight.getSequenceModelFast());
        payload.put("transformerScore", insight.getTransformerScore());
        payload.put("tcnScore", insight.getTcnScore());
        payload.put("sequenceAnomalyScore", insight.getSequenceAnomalyScore());
        payload.put("sequenceCategoricalScore", insight.getSequenceCategoricalScore());
        payload.put("sequenceContinuousScore", insight.getSequenceContinuousScore());
        payload.put("sequenceContextScore", insight.getSequenceContextScore());
        payload.put("aiRiskScore", insight.getAiRiskScore());
        payload.put("ruleRiskScore", insight.getRuleRiskScore());
        payload.put("finalRiskScore", insight.getFinalRiskScore());
        payload.put("xgboostAnomalyScore", insight.getXgboostAnomalyScore());
        payload.put("xgboostAnomalyScore100", insight.getXgboostAnomalyScore100());
payload.put("lightgbmAlertScore", insight.getLightgbmAlertScore());
        payload.put("lightgbmAlertScore100", insight.getLightgbmAlertScore100());
        payload.put("catboostAnomalyScore", insight.getCatboostAnomalyScore());
        payload.put("catboostAnomalyScore100", insight.getCatboostAnomalyScore100());
        payload.put("oneClassSvmNoveltyScoreRaw", insight.getOneClassSvmNoveltyScoreRaw());
        payload.put("oneClassSvmNoveltyScore100", insight.getOneClassSvmNoveltyScore100());
        payload.put("transformerRiskScore100", insight.getTransformerRiskScore100());
        payload.put("tcnRiskScore100", insight.getTcnRiskScore100());
        payload.put("modelScores", insight.getModelScores());
        payload.put("modelContributions", insight.getModelContributions());
        payload.put("riskFusionWeights", insight.getRiskFusionWeights());
        payload.put("fallbackMode", insight.getFallbackMode());
        payload.put("sequenceTopContributions", insight.getSequenceTopContributions());
        payload.put("anomalyTypeSource", insight.getAnomalyTypeSource());
        payload.put("anomalyTypeEvidence", insight.getAnomalyTypeEvidence());
        payload.put("churnRiskLevel", insight.getChurnRiskLevel());
        payload.put("churnModelName", insight.getChurnModelName());
        payload.put("forecastTotalEvents", insight.getForecastTotalEvents());
        payload.put("forecastAnomalyRate", insight.getForecastAnomalyRate());
        payload.put("forecastExpectedAlertVolume", insight.getForecastExpectedAlertVolume());
        payload.put("forecastContext", insight.getForecastContext());
        payload.put("modelArtifacts", insight.getModelArtifacts());
        payload.put("llmEvidencePayloadAvailable", insight.getLlmExplanationEvidencePayload() != null);
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
        cacheUser360(summary, insight);
    }

    private boolean defaultBoolean(Integer value) {
        return value != null && value == 1;
    }

    private void cacheUser360(SessionSummary summary, SessionInsight insight) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("insuredId", summary.getInsuredId());
        payload.put("personaEnabled", false);
        payload.put("personaLabel", insight.getPersonaLabel());
        payload.put("churnProbability", insight.getChurnProbability());
        payload.put("churnRiskLevel", insight.getChurnRiskLevel());
        payload.put("averageRiskScoreLast30d", insight.getFinalRiskScore());
        payload.put("alertCountLast30d", insight.isAnomaly() ? 1 : 0);
        payload.put("criticalAlertCountLast30d", "CRITICAL".equalsIgnoreCase(insight.getRiskLevel()) ? 1 : 0);
        payload.put("usualCountry", summary.getCountryCode());
        payload.put("usualDevice", null);
        payload.put("usualBrowser", null);
        List<Integer> activeHours = new ArrayList<>();
        if (summary.getStartHour() != null) {
            activeHours.add(summary.getStartHour());
        }
        if (summary.getEndHour() != null) {
            activeHours.add(summary.getEndHour());
        }
        payload.put("usualActiveHours", activeHours);
        payload.put("topApiFamilies", List.of());
        payload.put("recentSessions", List.of(Map.of(
                "sessionId", summary.getSessionId(),
                "riskLevel", insight.getRiskLevel(),
                "finalRiskScore", insight.getFinalRiskScore() == null ? 0.0 : insight.getFinalRiskScore())));
        payload.put("riskTimeline", List.of(Map.of(
                "timestamp", insight.getComputedAt(),
                "finalRiskScore", insight.getFinalRiskScore() == null ? 0.0 : insight.getFinalRiskScore())));
        redisCacheService.setJson(CacheKeys.user360Key(summary.getInsuredId()), payload, cacheProperties.getSessionInsight());
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
        refreshSecurityOverview();
        refreshChurnDashboard();
        refreshForecastDashboardV36();
        cacheDashboard("model-health", modelHealthService.snapshot());
    }

    public void refreshAlertsFeed() {
        List<Map<String, Object>> alerts = anomalyEventRepository.findTop100ByOrderByDetectedAtDesc().stream()
                .map(this::alertRow)
                .toList();
        if (alerts.isEmpty() && anomalyEventRepository.count() == 0) {
            alerts = runtimeExport("alerts_feed.csv");
        }
        cacheDashboard("alerts", Map.of(
                "items", alerts,
                "generatedAt", Instant.now().toString()));
    }

    public void refreshRiskySessions() {
        List<Map<String, Object>> rows = new ArrayList<>(activeSessionInsights());
        sessionAnalysisRepository.findTop20ByOrderByFinalRiskScoreDescCreatedAtDesc().stream()
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
        if (sessions.isEmpty()) {
            cacheDashboard("drop-offs", Map.of(
                    "items", runtimeExport("dropoff_actions.csv"),
                    "generatedAt", Instant.now().toString()));
            return;
        }
        cacheDashboard("drop-offs", Map.of(
                "items", List.of(),
                "generatedAt", Instant.now().toString()));
    }

    public void refreshPathDeviations() {
        List<Map<String, Object>> rows = runtimeExport("path_deviations.csv");
        if (rows.isEmpty()) {
            rows = runtimeExport("path_summary.csv");
        }
        cacheDashboard("path-deviations", Map.of(
                "items", rows,
                "generatedAt", Instant.now().toString()));
    }

    public void refreshForecasts() {
        Map<String, Object> payload = buildForecastSnapshot(LocalDate.now(ZoneOffset.UTC));
        cacheDashboard("forecasts", payload);
        redisCacheService.setJson(CacheKeys.forecastDashboardV36Key(), buildForecastDashboardPayload(LocalDate.now(ZoneOffset.UTC)), cacheProperties.getForecast());
    }

    public void refreshSecurityOverview() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long totalEvents = statisticsService.countEventsForDate(today);
        long alerts = statisticsService.countAlertsForDate(today);
        ForecastPrediction forecast = forecastRuntimeService.forecast(today);
        List<SessionAnalysis> sessions = sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc();

Map<String, Long> topRules = new LinkedHashMap<>();
        double riskTotal = 0.0;
        int riskCount = 0;
        long critical = 0L;
        long high = 0L;
        for (SessionAnalysis session : sessions) {
            Object parsedRules = parseJsonValue(session.getTriggeredRulesJson());
            if (parsedRules instanceof List<?> rules) {
                for (Object rule : rules) {
                    String code = String.valueOf(rule);
                    topRules.put(code, topRules.getOrDefault(code, 0L) + 1L);
                }
            }
            if (session.getFinalRiskScore() != null) {
                riskTotal += session.getFinalRiskScore();
                riskCount++;
                if (session.getFinalRiskScore() >= 80.0) {
                    critical++;
                } else if (session.getFinalRiskScore() >= 60.0) {
                    high++;
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("snapshotTimestamp", Instant.now().toString());
        payload.put("totalEventsToday", totalEvents);
        payload.put("activeUsersToday", activeSessionInsights().stream().map(row -> row.get("insuredId")).distinct().count());
        payload.put("anomalyRateToday", totalEvents == 0 ? 0.0 : (double) alerts / totalEvents);
        payload.put("criticalAlertsToday", critical);
        payload.put("highRiskAlertsToday", high);
        payload.put("averageRiskScoreToday", riskCount == 0 ? 0.0 : riskTotal / riskCount);
        payload.put("predictedAnomalyRateTomorrow", forecast.getAnomalyRateForecast());
        payload.put("predictedTotalEventsTomorrow", forecast.getTotalEventsForecast());
        payload.put("expectedAlertVolumeTomorrow", forecast.getExpectedAlertVolume());
        payload.put("topAnomalyTypes", Map.of());
        payload.put("topTriggeredRules", topNMap(topRules, 5));
        payload.put("modelHealthSummary", modelHealthService.snapshot());
        payload.put("fieldCoverageWarnings", modelHealthService.snapshot().get("highUnknownFieldWarnings"));
        redisCacheService.setJson(CacheKeys.securityOverviewDashboardKey(), payload, cacheProperties.getDashboard());
    }

    public void refreshChurnDashboard() {
        List<SessionAnalysis> sessions = sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc();
        int high = 0;
        int medium = 0;
        int low = 0;
        double total = 0.0;
        int count = 0;
        List<Map<String, Object>> users = new ArrayList<>();
        for (SessionAnalysis session : sessions) {
            Double probability = session.getChurnProbability();
            if (probability != null) {
                total += probability;
                count++;
            }
            String risk = session.getChurnRiskLevel();
            if ("HIGH".equalsIgnoreCase(risk)) {
                high++;
            } else if ("MEDIUM".equalsIgnoreCase(risk)) {
                medium++;
            } else if ("LOW".equalsIgnoreCase(risk)) {
                low++;
            }
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("insuredId", session.getInsuredId());
            user.put("sessionId", session.getSessionId());
            user.put("churnProbability", probability);
            user.put("churnRiskLevel", risk);
            users.add(user);
        }
        users = users.stream()
                .sorted(Comparator.comparing((Map<String, Object> row) -> numeric(row.get("churnProbability"))).reversed())
                .limit(10)
                .toList();
        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("HIGH", high);
        distribution.put("MEDIUM", medium);
        distribution.put("LOW", low);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("totalUsers", sessions.stream().map(SessionAnalysis::getInsuredId).distinct().count());
        payload.put("highChurnRiskUsers", high);
        payload.put("mediumChurnRiskUsers", medium);
        payload.put("lowChurnRiskUsers", low);
        payload.put("averageChurnProbability", count == 0 ? 0.0 : total / count);
        payload.put("topChurnRiskUsers", users);
        payload.put("churnRiskDistribution", distribution);
        redisCacheService.setJson(CacheKeys.churnDashboardKey(), payload, cacheProperties.getDashboard());
    }

    public void refreshForecastDashboardV36() {
        redisCacheService.setJson(CacheKeys.forecastDashboardV36Key(), buildForecastDashboardPayload(LocalDate.now(ZoneOffset.UTC)), cacheProperties.getForecast());
    }

    public Map<String, Object> buildForecastSnapshot(LocalDate referenceDate) {
        LocalDate effectiveDate = referenceDate == null ? LocalDate.now(ZoneOffset.UTC) : referenceDate;
        long totalEventsActual = statisticsService.countEventsForDate(effectiveDate);
        long anomalyEventsActual = statisticsService.countAlertsForDate(effectiveDate);
        long downloadEventsActual = statisticsService.countDownloadsForDate(effectiveDate);
        ForecastPrediction forecast = forecastRuntimeService.forecast(effectiveDate);

        Map<String, Object> items = new LinkedHashMap<>();
        Map<String, Object> totalEvents = new LinkedHashMap<>();
        totalEvents.put("seriesKey", "total_events");
        totalEvents.put("label", "Total events");
        totalEvents.put("actualCount", totalEventsActual);
        totalEvents.put("forecast", forecast.getTotalEventsForecast());
        totalEvents.put("delta", forecast.getTotalEventsForecast() == null ? null : totalEventsActual - forecast.getTotalEventsForecast());
        totalEvents.put("modelArtifact", forecast.getTotalEventsModelArtifact());
        totalEvents.put("features", forecast.getTotalEventsFeatures());
        items.put("total_events", totalEvents);

        Map<String, Object> anomalyRate = new LinkedHashMap<>();
        anomalyRate.put("seriesKey", "anomaly_rate");
        anomalyRate.put("label", "Anomaly rate");
        anomalyRate.put("actualCount", anomalyEventsActual);
        anomalyRate.put("actualRate", totalEventsActual == 0 ? 0.0 : (double) anomalyEventsActual / totalEventsActual);
        anomalyRate.put("forecast", forecast.getAnomalyRateForecast());
        anomalyRate.put("strategy", forecast.getAnomalyRateStrategy());
        items.put("anomaly_rate", anomalyRate);

        Map<String, Object> downloads = new LinkedHashMap<>();
        downloads.put("seriesKey", "download_events");
        downloads.put("label", "Download events");
        downloads.put("actualCount", downloadEventsActual);
        items.put("download_events", downloads);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("referenceDate", effectiveDate);
        payload.put("hasLiveTraffic", totalEventsActual > 0);
        payload.put("generatedAt", Instant.now().toString());
        payload.put("items", items);
        payload.put("warnings", forecast.getWarnings());
        return payload;
    }

    private Map<String, Object> buildForecastDashboardPayload(LocalDate referenceDate) {
        LocalDate effectiveDate = referenceDate == null ? LocalDate.now(ZoneOffset.UTC) : referenceDate;
        ForecastPrediction forecast = forecastRuntimeService.forecast(effectiveDate);
        long historicalTotalEvents = statisticsService.countEventsForDate(effectiveDate);
        long historicalAlerts = statisticsService.countAlertsForDate(effectiveDate);
        Map<String, Object> modelNames = new LinkedHashMap<>();
        modelNames.put("totalEvents", forecast.getTotalEventsModelName());
        modelNames.put("anomalyRate", forecast.getAnomalyRateModelName());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("forecastDate", forecast.getForecastDate());
        payload.put("predictedTotalEvents", forecast.getTotalEventsForecast());
        payload.put("predictedAnomalyRate", forecast.getAnomalyRateForecast());
        payload.put("expectedAlertVolume", forecast.getExpectedAlertVolume());
        payload.put("historicalTotalEvents", historicalTotalEvents);
        payload.put("historicalAnomalyRate", historicalTotalEvents == 0 ? 0.0 : (double) historicalAlerts / historicalTotalEvents);
        payload.put("forecastModelNames", modelNames);
        payload.put("forecastWarnings", forecast.getWarnings());
        return payload;
    }

    private Map<String, Long> topNMap(Map<String, Long> counts, int limit) {
        Map<String, Long> ordered = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    private void cacheDashboard(String view, Object payload) {
        redisCacheService.setJson(CacheKeys.dashboardKey(view), withSchemaVersion(payload), cacheProperties.getDashboard());
        redisCacheService.publishJson(pubSubProperties.getLiveStatsChannel(), Map.of("refresh", view));
    }

    private Object withSchemaVersion(Object payload) {
        if (!(payload instanceof Map<?, ?> map) || map.containsKey("schemaVersion")) {
            return payload;
        }
        Map<String, Object> versioned = new LinkedHashMap<>();
        versioned.put("schemaVersion", "v3.6.1");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            versioned.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return versioned;
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
            try {
                Map<String, Object> value = redisCacheService.getJson(key, new TypeReference<Map<String, Object>>() { });
                if (value != null) {
                    rows.add(value);
                    continue;
                }
            } catch (Exception ex) {
                log.warn("Stale or invalid insight key {}, removing from index", key);
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
            if (key.contains(":index") || key.contains(":index:")) {
                continue;
            }
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
row.put("personaLabel", event.getPersonaLabel());
        row.put("aiRiskScore", event.getAiRiskScore());
        row.put("ruleRiskScore", event.getRuleRiskScore());
        row.put("finalRiskScore", event.getFinalRiskScore());
        row.put("anomalyTypeSource", event.getAnomalyTypeSource());
        row.put("eventContext", parseJsonValue(event.getEventJson()));
        row.put("detectedAt", event.getDetectedAt());
        return row;
    }

private Map<String, Object> sessionRow(SessionAnalysis session) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sessionId", session.getSessionId());
        row.put("insuredId", session.getInsuredId());
        row.put("countryCode", session.getCountryCode());
        row.put("churnProbability", session.getChurnProbability());
        row.put("personaCluster", session.getPersonaCluster());
        row.put("personaLabel", session.getPersonaLabel());
        row.put("personaSource", session.getPersonaSource());
        row.put("personaConfidence", session.getPersonaConfidence());
        row.put("sequenceModelArtifact", session.getSequenceModelArtifact());
        row.put("sequenceAnomalyScore", session.getSequenceAnomalyScore());
        row.put("sequenceCategoricalScore", session.getSequenceCatScore());
        row.put("sequenceContinuousScore", session.getSequenceContScore());
        row.put("sequenceContextScore", session.getSequenceCtxScore());
        row.put("aiRiskScore", session.getAiRiskScore());
        row.put("ruleRiskScore", session.getRuleRiskScore());
        row.put("finalRiskScore", session.getFinalRiskScore());
        row.put("churnRiskLevel", session.getChurnRiskLevel());
        row.put("modelArtifacts", parseJsonValue(session.getModelArtifactsJson()));
        row.put("topSequenceSurpriseFields", parseJsonValue(session.getTopSequenceSurpriseFieldsJson()));
        row.put("warnings", parseJsonValue(session.getWarningsJson()));
        row.put("triggeredRules", parseJsonValue(session.getTriggeredRulesJson()));
        row.put("actionSequence", parseJsonValue(session.getActionSequenceJson()));
        row.put("routeSequence", parseJsonValue(session.getRouteSequenceJson()));
        row.put("actionCounts", parseJsonValue(session.getActionCountsJson()));
        row.put("sessionStart", session.getStartTime());
        row.put("sessionEnd", session.getEndTime());
        row.put("totalEvents", session.getTotalEvents());
        row.put("sessionDurationSeconds", session.getSessionDurationSeconds());
        row.put("uniqueActions", session.getUniqueActions());
        return row;
    }

    private List<Map<String, Object>> runtimeExport(String name) {
        return List.of();
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

}
