package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.forecast.ForecastPrediction;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.PerformanceProperties;
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
import org.springframework.beans.factory.ObjectFactory;
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
import org.springframework.data.domain.PageRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardSnapshotService {

    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final AnomalyEventRepository anomalyEventRepository;
    private final ForecastRuntimeService forecastRuntimeService;
    private final ObjectFactory<ModelHealthService> modelHealthServiceFactory;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final StatisticsService statisticsService;
    private final RedisPubSubProperties pubSubProperties;
    private final PerformanceProperties performanceProperties;
    private final DashboardSnapshotPersistenceService dashboardSnapshotPersistenceService;

    private final AtomicReference<Instant> dashboardLastRefreshAt = new AtomicReference<>();
    private final AtomicInteger dashboardRefreshSkippedDueToRateLimit = new AtomicInteger();
    private final AtomicReference<Instant> lastRefreshAttemptAt = new AtomicReference<>();
    private final AtomicReference<String> lastRefreshError = new AtomicReference<>();
    private final AtomicLong refreshSuccessCount = new AtomicLong();
    private final AtomicLong refreshFailureCount = new AtomicLong();
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    private final AtomicLong refreshAlreadyRunningSkipped = new AtomicLong();

    private final AtomicReference<Instant> lastSecurityOverviewRefresh = new AtomicReference<>();
    private final AtomicReference<Instant> lastAlertsRefresh = new AtomicReference<>();
    private final AtomicReference<Instant> lastRiskySessionsRefresh = new AtomicReference<>();
    private final AtomicReference<Instant> lastModelHealthRefresh = new AtomicReference<>();

    private final AtomicLong lastAlertsRefreshMs = new AtomicLong();
    private final AtomicLong lastSecurityOverviewRefreshMs = new AtomicLong();
    private final AtomicLong lastRiskySessionsRefreshMs = new AtomicLong();
    private final AtomicLong lastTotalDashboardRefreshMs = new AtomicLong();
    private final AtomicReference<String> lastSlowDashboardView = new AtomicReference<>();
    private final AtomicLong lastSlowDashboardViewMs = new AtomicLong();
    private final AtomicReference<Instant> lastRefreshStartedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastRefreshCompletedAt = new AtomicReference<>();

    private volatile boolean alertsDirty = false;
    private volatile boolean riskySessionsDirty = false;
    private volatile boolean securityOverviewDirty = false;

    public void markDirty() {
        alertsDirty = true;
        riskySessionsDirty = true;
        securityOverviewDirty = true;
    }

    public void markAlertsDirty() {
        alertsDirty = true;
    }

    public void markOverviewDirty() {
        securityOverviewDirty = true;
    }

    public void markRiskySessionsDirty() {
        riskySessionsDirty = true;
    }

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
        markOverviewDirty();
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

    public boolean tryStartRefresh() {
        if (refreshInProgress.getAndSet(true)) {
            refreshAlreadyRunningSkipped.incrementAndGet();
            return false;
        }
        lastRefreshStartedAt.set(Instant.now());
        return true;
    }

    public void finishRefresh() {
        refreshInProgress.set(false);
        lastRefreshCompletedAt.set(Instant.now());
    }

    public void refreshAll() {
        if (!tryStartRefresh()) {
            log.debug("Dashboard refresh already running, skipping");
            return;
        }
        lastRefreshAttemptAt.set(Instant.now());
        long totalStart = System.currentTimeMillis();
        try {
            refreshSecurityOverview();
            refreshRiskySessions();
            refreshAlertsFeed();
            refreshClusterMix();
            refreshDropOffs();
            refreshPathDeviations();
            refreshForecasts();
            refreshChurnDashboard();
            refreshForecastDashboardV36();
            cacheDashboard("model-health", modelHealthServiceFactory.getObject().snapshot());
            dashboardLastRefreshAt.set(Instant.now());
            refreshSuccessCount.incrementAndGet();
        } catch (Exception e) {
            refreshFailureCount.incrementAndGet();
            lastRefreshError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Dashboard refreshAll failed", e);
            throw e;
        } finally {
            finishRefresh();
            lastTotalDashboardRefreshMs.set(System.currentTimeMillis() - totalStart);
            long totalMs = lastTotalDashboardRefreshMs.get();
            if (totalMs > 10000) {
                log.error("Dashboard refresh total took {}ms", totalMs);
            } else if (totalMs > 2000) {
                log.warn("Dashboard refresh total took {}ms", totalMs);
            }
        }
    }

    public void refreshAlertsFeed() {
        if (!canRefresh(lastAlertsRefresh, performanceProperties.getDashboardRefresh().getAlertsMinIntervalMs())) {
            if (lastAlertsRefresh.get() != null && alertsDirty) {
                dashboardRefreshSkippedDueToRateLimit.incrementAndGet();
            }
            alertsDirty = false;
            return;
        }
        long startMs = System.currentTimeMillis();
        long redisReadMs = 0;
        long sqlReadMs = 0;
        long jsonParseMs = 0;
        long sortFilterMs = 0;
        long redisWriteMs = 0;
        int itemCount = 0;

        List<Map<String, Object>> alerts = new ArrayList<>();

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> liveAlertsRaw = (List<Map<String, Object>>) (List<?>) redisCacheService.getJsonList(CacheKeys.liveAlertsV36Key(), Map.class);
        redisReadMs = System.currentTimeMillis() - t0;
        if (liveAlertsRaw != null && !liveAlertsRaw.isEmpty()) {
            t0 = System.currentTimeMillis();
            for (Map<String, Object> alert : liveAlertsRaw) {
                if (alert != null) {
                    alerts.add(alert);
                }
            }
            jsonParseMs = System.currentTimeMillis() - t0;
        }

        if (alerts.size() < performanceProperties.getDashboardRefresh().getMaxAlertItems()) {
            t0 = System.currentTimeMillis();
            int needed = performanceProperties.getDashboardRefresh().getMaxAlertItems() - alerts.size();
            List<AnomalyEvent> sqlAlerts = anomalyEventRepository.findRecentAnomalyEvents(PageRequest.of(0, needed));
            sqlReadMs = System.currentTimeMillis() - t0;
            t0 = System.currentTimeMillis();
            for (AnomalyEvent event : sqlAlerts) {
                alerts.add(alertRow(event));
            }
            jsonParseMs += System.currentTimeMillis() - t0;
        }

        t0 = System.currentTimeMillis();
        alerts = alerts.stream()
                .sorted(Comparator.comparing((Map<String, Object> row) -> {
                    Object val = row.get("detectedAt");
                    return val instanceof Instant ? (Instant) val : Instant.MIN;
                }).reversed())
                .limit(performanceProperties.getDashboardRefresh().getMaxAlertItems())
                .toList();
        sortFilterMs = System.currentTimeMillis() - t0;
        itemCount = alerts.size();

        if (alerts.isEmpty() && liveAlertsRaw == null) {
            alerts = runtimeExport("alerts_feed.csv");
            itemCount = alerts.size();
        }

        t0 = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", alerts);
        payload.put("generatedAt", Instant.now().toString());
        payload.put("updatedAt", Instant.now().toString());
        payload.put("source", "redis_live_incremental");
        payload.put("itemCount", itemCount);
        cacheDashboard("alerts", payload);
        redisWriteMs = System.currentTimeMillis() - t0;

        lastAlertsRefresh.set(Instant.now());
        alertsDirty = false;
        dashboardLastRefreshAt.set(Instant.now());
        long totalMs = System.currentTimeMillis() - startMs;
        lastAlertsRefreshMs.set(totalMs);

        log.info("Dashboard view refresh completed view=alerts totalMs={} redisReadMs={} sqlReadMs={} jsonParseMs={} sortFilterMs={} redisWriteMs={} itemCount={}",
                totalMs, redisReadMs, sqlReadMs, jsonParseMs, sortFilterMs, redisWriteMs, itemCount);
        if (totalMs > 2000) {
            log.warn("Dashboard view refresh slow view=alerts totalMs={}", totalMs);
            lastSlowDashboardView.set("alerts");
            lastSlowDashboardViewMs.set(totalMs);
        }
    }

    public void refreshRiskySessions() {
        if (!canRefresh(lastRiskySessionsRefresh, performanceProperties.getDashboardRefresh().getRiskySessionsMinIntervalMs())) {
            if (lastRiskySessionsRefresh.get() != null && riskySessionsDirty) {
                dashboardRefreshSkippedDueToRateLimit.incrementAndGet();
            }
            riskySessionsDirty = false;
            return;
        }
        long startMs = System.currentTimeMillis();
        long redisReadMs = 0;
        long sqlReadMs = 0;
        long jsonParseMs = 0;
        long sortFilterMs = 0;
        long redisWriteMs = 0;
        int itemCount = 0;

        List<Map<String, Object>> rows = new ArrayList<>();

        long t0 = System.currentTimeMillis();
        for (Map<String, Object> insight : activeSessionInsights()) {
            rows.add(insight);
        }
        redisReadMs = System.currentTimeMillis() - t0;

        int maxItems = performanceProperties.getDashboardRefresh().getMaxRiskySessionItems();
        if (rows.size() < maxItems) {
            t0 = System.currentTimeMillis();
            int needed = maxItems - rows.size();
            sessionAnalysisRepository.findTopRiskySessions(PageRequest.of(0, needed)).stream()
                    .map(this::sessionRow)
                    .forEach(rows::add);
            sqlReadMs = System.currentTimeMillis() - t0;
        }

        t0 = System.currentTimeMillis();
        rows = rows.stream()
                .sorted(Comparator.comparing((Map<String, Object> row) -> numeric(row.get("riskScore"))).reversed())
                .limit(maxItems)
                .toList();
        sortFilterMs = System.currentTimeMillis() - t0;
        itemCount = rows.size();

        if (rows.isEmpty()) {
            rows = runtimeExport("top_risky_sessions.csv");
            itemCount = rows.size();
        }

        t0 = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", rows);
        payload.put("generatedAt", Instant.now().toString());
        payload.put("updatedAt", Instant.now().toString());
        payload.put("source", "redis_live_incremental");
        payload.put("itemCount", itemCount);
        cacheDashboard("risky-sessions", payload);
        redisWriteMs = System.currentTimeMillis() - t0;

        lastRiskySessionsRefresh.set(Instant.now());
        riskySessionsDirty = false;
        dashboardLastRefreshAt.set(Instant.now());
        long totalMs = System.currentTimeMillis() - startMs;
        lastRiskySessionsRefreshMs.set(totalMs);

        log.info("Dashboard view refresh completed view=risky-sessions totalMs={} redisReadMs={} sqlReadMs={} jsonParseMs={} sortFilterMs={} redisWriteMs={} itemCount={}",
                totalMs, redisReadMs, sqlReadMs, jsonParseMs, sortFilterMs, redisWriteMs, itemCount);
        if (totalMs > 2000) {
            log.warn("Dashboard view refresh slow view=risky-sessions totalMs={}", totalMs);
            lastSlowDashboardView.set("risky-sessions");
            lastSlowDashboardViewMs.set(totalMs);
        }
    }

    public void refreshClusterMix() {
        List<SessionAnalysis> sessions = sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc();
        if (sessions.isEmpty()) {
            cacheDashboard("cluster-mix", Map.of(
                    "items", runtimeExport("cluster_mix.csv"),
                    "generatedAt", Instant.now().toString(),
                    "updatedAt", Instant.now().toString(),
                    "source", "sql_live",
                    "itemCount", 0));
            return;
        }
        Map<Integer, Long> counts = new LinkedHashMap<>();
        for (SessionAnalysis session : sessions) {
            if (session.getPersonaCluster() == null) {
                continue;
            }
            counts.put(session.getPersonaCluster(), counts.getOrDefault(session.getPersonaCluster(), 0L) + 1);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<Map<String, Object>> rows = counts.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "cluster_id", entry.getKey(),
                        "traffic_share", total == 0 ? 0.0 : (double) entry.getValue() / total))
                .toList();
        cacheDashboard("cluster-mix", Map.of(
                "items", rows,
                "generatedAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString(),
                "source", "sql_live",
                "itemCount", rows.size()));
    }

    public void refreshDropOffs() {
        List<SessionAnalysis> sessions = sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc();
        if (sessions.isEmpty()) {
            cacheDashboard("drop-offs", Map.of(
                    "items", runtimeExport("dropoff_actions.csv"),
                    "generatedAt", Instant.now().toString(),
                    "updatedAt", Instant.now().toString(),
                    "source", "sql_live",
                    "itemCount", 0));
            return;
        }
        cacheDashboard("drop-offs", Map.of(
                "items", List.of(),
                "generatedAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString(),
                "source", "sql_live",
                "itemCount", 0));
    }

    public void refreshPathDeviations() {
        List<Map<String, Object>> rows = runtimeExport("path_deviations.csv");
        if (rows.isEmpty()) {
            rows = runtimeExport("path_summary.csv");
        }
        cacheDashboard("path-deviations", Map.of(
                "items", rows,
                "generatedAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString(),
                "source", "runtime_export",
                "itemCount", rows.size()));
    }

    public void refreshForecasts() {
        Map<String, Object> payload = buildForecastSnapshot(LocalDate.now(ZoneOffset.UTC));
        cacheDashboard("forecasts", payload);
        redisCacheService.setJson(CacheKeys.forecastDashboardV36Key(), buildForecastDashboardPayload(LocalDate.now(ZoneOffset.UTC)), cacheProperties.getForecast());
    }

    public void refreshSecurityOverview() {
        if (!canRefresh(lastSecurityOverviewRefresh, performanceProperties.getDashboardRefresh().getSecurityOverviewMinIntervalMs())) {
            if (lastSecurityOverviewRefresh.get() != null && securityOverviewDirty) {
                dashboardRefreshSkippedDueToRateLimit.incrementAndGet();
            }
            securityOverviewDirty = false;
            return;
        }
        long startMs = System.currentTimeMillis();
        long redisReadMs = 0;
        long forecastMs = 0;
        long sqlReadMs = 0;
        long aggregationMs = 0;
        long redisWriteMs = 0;
        long eventCount = 0;
        long alertCount = 0;
        long sessionCount = 0;

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        long t0 = System.currentTimeMillis();
        long totalEvents = statisticsService.countEventsForDate(today);
        eventCount = totalEvents;
        long alerts = statisticsService.countAlertsForDate(today);
        alertCount = alerts;
        redisReadMs = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        List<SessionAnalysis> sessions = sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc();
        sqlReadMs = System.currentTimeMillis() - t0;
        sessionCount = sessions.size();

        t0 = System.currentTimeMillis();
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
        aggregationMs = System.currentTimeMillis() - t0;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("snapshotTimestamp", Instant.now().toString());
        payload.put("generatedAt", Instant.now().toString());
        payload.put("updatedAt", Instant.now().toString());
        payload.put("source", "redis_live_incremental");
        payload.put("totalEventsToday", totalEvents);
        payload.put("eventCount", eventCount);
        payload.put("activeUsersToday", activeSessionInsights().stream().map(row -> row.get("insuredId")).distinct().count());
        payload.put("anomalyRateToday", totalEvents == 0 ? 0.0 : (double) alerts / totalEvents);
        payload.put("criticalAlertsToday", critical);
        payload.put("highRiskAlertsToday", high);
        payload.put("alertCount", alertCount);
        payload.put("sessionCount", sessionCount);
        payload.put("averageRiskScoreToday", riskCount == 0 ? 0.0 : riskTotal / riskCount);
        payload.put("predictedAnomalyRateTomorrow", null);
        payload.put("predictedTotalEventsTomorrow", null);
        payload.put("expectedAlertVolumeTomorrow", null);
        payload.put("topAnomalyTypes", Map.of());
        payload.put("topTriggeredRules", topNMap(topRules, 5));
        payload.put("modelHealthSummary", modelHealthServiceFactory.getObject().snapshot());
        payload.put("fieldCoverageWarnings", modelHealthServiceFactory.getObject().snapshot().get("highUnknownFieldWarnings"));

        t0 = System.currentTimeMillis();
        redisCacheService.setJson(CacheKeys.securityOverviewDashboardKey(), payload, cacheProperties.getDashboard());
        redisWriteMs = System.currentTimeMillis() - t0;
        dashboardSnapshotPersistenceService.persistSnapshot("security-overview", "security-overview:latest", payload, "dashboard_refresh");

        lastSecurityOverviewRefresh.set(Instant.now());
        securityOverviewDirty = false;
        dashboardLastRefreshAt.set(Instant.now());
        long totalMs = System.currentTimeMillis() - startMs;
        lastSecurityOverviewRefreshMs.set(totalMs);

        log.info("Dashboard view refresh completed view=security-overview totalMs={} redisReadMs={} sqlReadMs={} forecastMs={} aggregationMs={} redisWriteMs={} eventCount={} alertCount={} sessionCount={}",
                totalMs, redisReadMs, sqlReadMs, forecastMs, aggregationMs, redisWriteMs, eventCount, alertCount, sessionCount);
        if (totalMs > 2000) {
            log.warn("Dashboard view refresh slow view=security-overview totalMs={}", totalMs);
            lastSlowDashboardView.set("security-overview");
            lastSlowDashboardViewMs.set(totalMs);
        }
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
        payload.put("generatedAt", Instant.now().toString());
        payload.put("updatedAt", Instant.now().toString());
        payload.put("source", "sql_live");
        redisCacheService.setJson(CacheKeys.churnDashboardKey(), payload, cacheProperties.getDashboard());
        dashboardSnapshotPersistenceService.persistSnapshot("churn-dashboard", "churn-dashboard:latest", payload, "dashboard_refresh");
    }

    public void refreshForecastDashboardV36() {
        Map<String, Object> payload = buildForecastDashboardPayload(LocalDate.now(ZoneOffset.UTC));
        redisCacheService.setJson(CacheKeys.forecastDashboardV36Key(), payload, cacheProperties.getForecast());
        dashboardSnapshotPersistenceService.persistSnapshot("forecast-dashboard", "forecast-dashboard:latest", payload, "dashboard_refresh");
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
        payload.put("updatedAt", Instant.now().toString());
        payload.put("source", "sql_live");
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

    private boolean canRefresh(AtomicReference<Instant> lastRefresh, long minIntervalMs) {
        Instant last = lastRefresh.get();
        if (last == null) return true;
        return System.currentTimeMillis() - last.toEpochMilli() >= minIntervalMs;
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
        Object versionedPayload = withSchemaVersion(payload);
        redisCacheService.setJson(CacheKeys.dashboardKey(view), versionedPayload, cacheProperties.getDashboard());
        redisCacheService.publishJson(pubSubProperties.getLiveStatsChannel(), Map.of("refresh", view));
        dashboardSnapshotPersistenceService.persistSnapshot(view, view + ":latest", versionedPayload, "dashboard_refresh");
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

    public Instant getDashboardLastRefreshAt() {
        return dashboardLastRefreshAt.get();
    }

    public int getDashboardRefreshSkippedDueToRateLimit() {
        return dashboardRefreshSkippedDueToRateLimit.get();
    }

    public Instant getLastRefreshAttemptAt() {
        return lastRefreshAttemptAt.get();
    }

    public String getLastRefreshError() {
        return lastRefreshError.get();
    }

    public long getRefreshSuccessCount() {
        return refreshSuccessCount.get();
    }

    public long getRefreshFailureCount() {
        return refreshFailureCount.get();
    }

    public boolean isAlertsDirty() {
        return alertsDirty;
    }

    public boolean isRiskySessionsDirty() {
        return riskySessionsDirty;
    }

    public boolean isSecurityOverviewDirty() {
        return securityOverviewDirty;
    }

    public long getLastAlertsRefreshMs() {
        return lastAlertsRefreshMs.get();
    }

    public long getLastSecurityOverviewRefreshMs() {
        return lastSecurityOverviewRefreshMs.get();
    }

    public long getLastRiskySessionsRefreshMs() {
        return lastRiskySessionsRefreshMs.get();
    }

    public long getLastTotalDashboardRefreshMs() {
        return lastTotalDashboardRefreshMs.get();
    }

    public String getLastSlowDashboardView() {
        return lastSlowDashboardView.get();
    }

    public long getLastSlowDashboardViewMs() {
        return lastSlowDashboardViewMs.get();
    }

    public long getRefreshAlreadyRunningSkipped() {
        return refreshAlreadyRunningSkipped.get();
    }

    public Instant getLastRefreshStartedAt() {
        return lastRefreshStartedAt.get();
    }

    public Instant getLastRefreshCompletedAt() {
        return lastRefreshCompletedAt.get();
    }
}