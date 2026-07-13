package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.StatsResponseDto;
import com.neo.dashboard.dto.v36.V36ChurnDashboardResponse;
import com.neo.dashboard.dto.v36.V36DiagnosticsResponse;
import com.neo.dashboard.dto.v36.V36FinalWinnersResponse;
import com.neo.dashboard.dto.v36.V36ForecastDashboardResponse;
import com.neo.dashboard.dto.v36.V36RuntimeHealthResponse;
import com.neo.dashboard.dto.v36.V36SecurityOverviewResponse;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class V36DashboardService {

    private static final String FINAL_WINNERS_RESOURCE = "AI/reports/final_use_case_winners.json";

    private final V36RedisReadService redisReadService;
    private final StatsService statsService;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final ObjectMapper objectMapper;
    private final DashboardSnapshotFallbackService snapshotFallbackService;
    private final StringRedisTemplate redisTemplate;
    private final V36User360Service user360Service;
    private final AnomalyEventRepository anomalyEventRepository;

    public V36RuntimeHealthResponse getRuntimeHealth() {
        DashboardSnapshotFallbackService.FallbackResult<V36RuntimeHealthResponse> result =
                snapshotFallbackService.readWithFallback(
                        CacheKeys.AI_RUNTIME_HEALTH_V36,
                        V36RuntimeHealthResponse.class,
                        "model-health",
                        "model-health:latest");
        if (result != null) {
            V36RuntimeHealthResponse response = normalizeRuntimeHealth(result.payload());
            response.setSource(result.source());
            return response;
        }
        V36RuntimeHealthResponse fallback = V36RuntimeHealthResponse.unknown();
        fallback.setSource("generated_fallback");
        return fallback;
    }

    public V36SecurityOverviewResponse getSecurityOverview() {
        DashboardSnapshotFallbackService.FallbackResult<V36SecurityOverviewResponse> result =
                snapshotFallbackService.readWithFallback(
                        CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36,
                        V36SecurityOverviewResponse.class,
                        "security-overview",
                        "security-overview:latest");
        if (result != null) {
            V36SecurityOverviewResponse response = normalizeSecurityOverview(result.payload());
            response.setSource(result.source());
            enrichTopAnomalyTypes(response);
            return response;
        }
        V36SecurityOverviewResponse fallback = buildSecurityOverviewFallback();
        fallback.setSource("generated_fallback");
        return fallback;
    }

    public V36DiagnosticsResponse getDiagnostics() {
        V36RuntimeHealthResponse runtimeHealth = getRuntimeHealth();
        Map<String, Object> fieldCoverage = new LinkedHashMap<>();
        redisReadService.readJson(CacheKeys.AI_SEQUENCE_FIELD_COVERAGE_V36)
                .ifPresent(node -> fieldCoverage.put("sequence", safeNodeToObject(node)));
        redisReadService.readJson(CacheKeys.AI_TABULAR_FIELD_COVERAGE_V36)
                .ifPresent(node -> fieldCoverage.put("tabular", safeNodeToObject(node)));
        Optional<Object> modelLatency = redisReadService.readJson(CacheKeys.AI_MODEL_LATENCY_V36)
                .map(this::safeNodeToObject);
        if (modelLatency.isEmpty() && runtimeHealth.getModelLatency() != null) {
            modelLatency = Optional.of(runtimeHealth.getModelLatency());
        }

        List<String> warnings = new ArrayList<>();
        if (fieldCoverage.isEmpty()) {
            warnings.add("V3.6.1 field coverage snapshots are not available");
        }
        if (modelLatency.isEmpty()) {
            warnings.add("V3.6.1 model latency snapshot is not available");
        }
        if (runtimeHealth.getWarnings() != null) {
            warnings.addAll(runtimeHealth.getWarnings());
        }

        Map<String, Object> kafka = runtimeHealth.getKafka();
        Map<String, Object> idempotency = runtimeHealth.getIdempotency();
        Map<String, Object> performance = runtimeHealth.getPerformance();
        Map<String, Object> statsSection = runtimeHealth.getStats();
        Map<String, Object> sessionFinalization = runtimeHealth.getSessionFinalization();

        return new V36DiagnosticsResponse(
                CacheKeys.V36_SCHEMA_VERSION,
                runtimeHealth,
                fieldCoverage,
                modelLatency.orElse(null),
                runtimeHealth.getFallbackMode(),
                warnings.stream().distinct().toList(),
                kafka,
                idempotency,
                performance,
                statsSection,
                sessionFinalization
        );
    }

    private Object safeNodeToObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            log.warn("Failed to convert JsonNode to plain object", e);
            return null;
        }
    }

    public V36ChurnDashboardResponse getChurnDashboard() {
        DashboardSnapshotFallbackService.FallbackResult<V36ChurnDashboardResponse> result =
                snapshotFallbackService.readWithFallback(
                        CacheKeys.DASHBOARD_CHURN_V36,
                        V36ChurnDashboardResponse.class,
                        "churn-dashboard",
                        "churn-dashboard:latest");
        if (result != null) {
            V36ChurnDashboardResponse response = normalizeChurnDashboard(result.payload());
            response.setSource(result.source());
            return response;
        }
        V36ChurnDashboardResponse fallback = buildChurnFallback();
        fallback.setSource("generated_fallback");
        return fallback;
    }

    public V36ForecastDashboardResponse getForecastDashboard() {
        DashboardSnapshotFallbackService.FallbackResult<V36ForecastDashboardResponse> result =
                snapshotFallbackService.readWithFallback(
                        CacheKeys.DASHBOARD_FORECAST_V36,
                        V36ForecastDashboardResponse.class,
                        "forecast-dashboard",
                        "forecast-dashboard:latest");
        if (result != null) {
            V36ForecastDashboardResponse response = normalizeForecastDashboard(result.payload());
            response.setSource(result.source());
            hydrateHistoricalSeriesIfEmpty(response);
            if (result.source().equals("sql_fallback")) {
                V36ForecastDashboardResponse finalResponse = response;
                List<String> warnings = finalResponse.getForecastWarnings() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(finalResponse.getForecastWarnings());
                warnings.add("SQL fallback: " + result.snapshotKey());
                finalResponse.setForecastWarnings(warnings);
            }
            return response;
        }
        V36ForecastDashboardResponse fallback = buildForecastFallback();
        fallback.setSource("generated_fallback");
        hydrateHistoricalSeriesIfEmpty(fallback);
        return fallback;
    }

    public V36FinalWinnersResponse getFinalWinners() {
        ClassPathResource resource = new ClassPathResource(FINAL_WINNERS_RESOURCE);
        if (!resource.exists()) {
            return V36FinalWinnersResponse.unavailable(FINAL_WINNERS_RESOURCE);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode payloadNode = objectMapper.readTree(inputStream);
            Object payload = payloadNode == null || payloadNode.isNull() || payloadNode.isMissingNode()
                    ? null
                    : objectMapper.treeToValue(payloadNode, Object.class);
            return new V36FinalWinnersResponse(
                    CacheKeys.V36_SCHEMA_VERSION,
                    true,
                    FINAL_WINNERS_RESOURCE,
                    Instant.now(),
                    List.of(),
                    payload
            );
        } catch (Exception e) {
            log.warn("Failed to read final winners report from classpath", e);
            V36FinalWinnersResponse response = V36FinalWinnersResponse.unavailable(FINAL_WINNERS_RESOURCE);
            response.setWarnings(List.of("Failed to parse final winners report"));
            return response;
        }
    }

    public Map<String, Object> getReportMetadata() {
        V36FinalWinnersResponse winners = getFinalWinners();
        return Map.of(
                "schemaVersion", CacheKeys.V36_SCHEMA_VERSION,
                "reports", List.of(Map.of(
                        "name", "final_use_case_winners",
                        "resource", FINAL_WINNERS_RESOURCE,
                        "available", winners.isAvailable()
                ))
        );
    }

    private V36RuntimeHealthResponse normalizeRuntimeHealth(V36RuntimeHealthResponse response) {
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        if (response.getStatus() == null || response.getStatus().isBlank()) {
            response.setStatus(resolveRuntimeStatus(response));
        }
        if (response.getRuntimeVersion() == null) {
            response.setRuntimeVersion(CacheKeys.V36_SCHEMA_VERSION);
        }
        if (response.getWarnings() == null) {
            response.setWarnings(List.of());
        }
        return response;
    }

    private String resolveRuntimeStatus(V36RuntimeHealthResponse response) {
        if (response.getModelHealth() == null || response.getModelHealth().isEmpty()) {
            return "UNKNOWN";
        }
        boolean anyUnavailable = response.getModelHealth().values().stream()
                .filter(Objects::nonNull)
                .anyMatch(state -> Boolean.FALSE.equals(state.getRuntimeInitialized())
                        && Boolean.TRUE.equals(state.getInferenceEnabledByConfig()));
        return anyUnavailable ? "DEGRADED" : "HEALTHY";
    }

    private V36SecurityOverviewResponse normalizeSecurityOverview(V36SecurityOverviewResponse response) {
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        if (response.getSnapshotTimestamp() == null) {
            response.setSnapshotTimestamp(Instant.now());
        }
        if (response.getTopAnomalyTypes() == null) {
            response.setTopAnomalyTypes(Map.of());
        }
        if (response.getTopTriggeredRules() == null) {
            response.setTopTriggeredRules(Map.of());
        }
        if (response.getModelHealthSummary() == null) {
            response.setModelHealthSummary(Map.of());
        }
        if (response.getFieldCoverageWarnings() == null) {
            response.setFieldCoverageWarnings(List.of());
        }
        return response;
    }

    private void enrichTopAnomalyTypes(V36SecurityOverviewResponse response) {
        if (response.getTopAnomalyTypes() != null && !response.getTopAnomalyTypes().isEmpty()) {
            return;
        }
        if (response.getHighRiskAlertsToday() <= 0 && response.getCriticalAlertsToday() <= 0) {
            return;
        }
        Instant todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Object[]> counts = anomalyEventRepository.countByAnomalyTypeSince(todayStart);
        if (counts == null || counts.isEmpty()) {
            response.setTopAnomalyTypes(Map.of());
            return;
        }
        response.setTopAnomalyTypes(counts.stream()
                .filter(row -> row[0] != null)
                .collect(Collectors.groupingBy(
                        row -> (String) row[0],
                        LinkedHashMap::new,
                        Collectors.summingLong(row -> (Long) row[1])
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                )));
    }

    private V36SecurityOverviewResponse buildSecurityOverviewFallback() {
        V36SecurityOverviewResponse response = new V36SecurityOverviewResponse();
        response.setSnapshotTimestamp(Instant.now());
        response.setTotalEventsToday(0L);
        response.setActiveUsersToday(0L);
        response.setAnomalyRateToday(0.0);
        response.setCriticalAlertsToday(0L);
        response.setHighRiskAlertsToday(0L);
        response.setAverageRiskScoreToday(0.0);
        response.setPredictedAnomalyRateTomorrow(0.0);
        response.setPredictedTotalEventsTomorrow(0L);
        response.setExpectedAlertVolumeTomorrow(0L);
        response.setTopAnomalyTypes(Map.of());
        response.setTopTriggeredRules(Map.of());
        response.setModelHealthSummary(Map.of("source", "fallback"));
        response.setFieldCoverageWarnings(List.of("V3.6.1 security overview snapshot not available"));
        return response;
    }

    private V36ChurnDashboardResponse normalizeChurnDashboard(V36ChurnDashboardResponse response) {
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        if (response.getTopChurnRiskUsers() == null) {
            response.setTopChurnRiskUsers(List.of());
        }
        if (response.getChurnRiskDistribution() == null) {
            response.setChurnRiskDistribution(Map.of());
        }
        return response;
    }

    private V36ChurnDashboardResponse buildChurnFallback() {
        List<SessionAnalysis> recent = sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc();
        List<SessionAnalysis> withChurn = recent.stream()
                .filter(session -> session.getChurnProbability() != null)
                .toList();

        Map<String, Long> distribution = withChurn.stream()
                .collect(Collectors.groupingBy(
                        session -> normalizeRiskLevel(session.getChurnRiskLevel()),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        double average = withChurn.stream()
                .map(SessionAnalysis::getChurnProbability)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        List<Map<String, Object>> topUsers = withChurn.stream()
                .collect(Collectors.groupingBy(
                        SessionAnalysis::getInsuredId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), user360Service::pickWinner)
                ))
                .values().stream()
                .sorted((left, right) -> Double.compare(
                        nullSafe(right.getChurnProbability()),
                        nullSafe(left.getChurnProbability())
                ))
                .limit(10)
                .map(session -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("insuredId", session.getInsuredId());
                    item.put("sessionId", session.getSessionId());
                    item.put("churnProbability", session.getChurnProbability());
                    item.put("churnRiskLevel", normalizeRiskLevel(session.getChurnRiskLevel()));
                    item.put("latestFinalRiskScore", session.getFinalRiskScore());
                    item.put("latestRiskLevel", session.getRiskLevel());
                    Map<String, Object> riskSummary = user360Service.computeUserRiskSummaryLast30d(session.getInsuredId());
                    item.put("averageRiskScoreLast30d", riskSummary.get("averageRiskScoreLast30d"));
                    item.put("alertCountLast30d", riskSummary.get("alertCountLast30d"));
                    item.put("criticalAlertCountLast30d", riskSummary.get("criticalAlertCountLast30d"));
                    return item;
                })
                .toList();

        V36ChurnDashboardResponse churnResponse = new V36ChurnDashboardResponse();
        churnResponse.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        churnResponse.setTotalUsers(withChurn.size());
        churnResponse.setHighChurnRiskUsers(distribution.getOrDefault("HIGH", 0L));
        churnResponse.setMediumChurnRiskUsers(distribution.getOrDefault("MEDIUM", 0L));
        churnResponse.setLowChurnRiskUsers(distribution.getOrDefault("LOW", 0L));
        churnResponse.setAverageChurnProbability(average);
        churnResponse.setTopChurnRiskUsers(topUsers);
        churnResponse.setChurnRiskDistribution(distribution);
        return churnResponse;
    }

    private V36ForecastDashboardResponse normalizeForecastDashboard(V36ForecastDashboardResponse response) {
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        if (response.getForecastDate() == null) {
            response.setForecastDate(LocalDate.now().plusDays(1));
        }
        response.setHistoricalTotalEvents(normalizeForecastSeries(
                response.getHistoricalTotalEvents(),
                response.getForecastDate(),
                "historical_total_events"));
        response.setHistoricalAnomalyRate(normalizeForecastSeries(
                response.getHistoricalAnomalyRate(),
                response.getForecastDate(),
                "historical_anomaly_rate"));
        if (response.getForecastModelNames() == null) {
            response.setForecastModelNames(Map.of());
        }
        if (response.getForecastWarnings() == null) {
            response.setForecastWarnings(List.of());
        }
        return response;
    }

    private void hydrateHistoricalSeriesIfEmpty(V36ForecastDashboardResponse response) {
        Object rawTotalEvents = response.getHistoricalTotalEvents();
        Object rawAnomalyRate = response.getHistoricalAnomalyRate();
        boolean totalEventsEmpty = rawTotalEvents == null
                || (rawTotalEvents instanceof List && ((List<?>) rawTotalEvents).isEmpty());
        boolean anomalyRateEmpty = rawAnomalyRate == null
                || (rawAnomalyRate instanceof List && ((List<?>) rawAnomalyRate).isEmpty());
        if (!totalEventsEmpty && !anomalyRateEmpty) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> totalEvents = new ArrayList<>();
        List<Map<String, Object>> anomalyRate = new ArrayList<>();
        boolean anyData = false;
        for (int i = 7; i >= 1; i--) {
            LocalDate date = today.minusDays(i);
            String eventsStr = redisTemplate.opsForValue().get("stats:events:day:" + date);
            String alertsStr = redisTemplate.opsForValue().get("stats:alerts:day:" + date);
            if (eventsStr != null && !eventsStr.isBlank()) {
                try {
                    long events = Long.parseLong(eventsStr);
                    if (events > 0) {
                        anyData = true;
                        Map<String, Object> ep = new LinkedHashMap<>();
                        ep.put("date", date.toString());
                        ep.put("value", events);
                        totalEvents.add(ep);
                        long alerts = (alertsStr != null && !alertsStr.isBlank())
                                ? Long.parseLong(alertsStr) : 0L;
                        Map<String, Object> ap = new LinkedHashMap<>();
                        ap.put("date", date.toString());
                        ap.put("value", (double) alerts / events);
                        anomalyRate.add(ap);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (totalEventsEmpty && !totalEvents.isEmpty()) {
            response.setHistoricalTotalEvents(totalEvents);
        }
        if (anomalyRateEmpty && !anomalyRate.isEmpty()) {
            response.setHistoricalAnomalyRate(anomalyRate);
        }
        List<String> warnings = response.getForecastWarnings() == null
                ? new ArrayList<>() : new ArrayList<>(response.getForecastWarnings());
        if (!anyData) {
            if (!warnings.contains("forecast_history_unavailable")) {
                warnings.add("forecast_history_unavailable");
            }
        } else if (totalEvents.size() < 7) {
            if (!warnings.contains("forecast_history_partial")) {
                warnings.add("forecast_history_partial");
            }
        }
        response.setForecastWarnings(warnings);
    }

    private V36ForecastDashboardResponse buildForecastFallback() {
        StatsResponseDto trendStats = statsService.getTrendStats(LocalDate.now());
        V36ForecastDashboardResponse response = new V36ForecastDashboardResponse();
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        response.setForecastDate(LocalDate.now().plusDays(1));
        response.setPredictedTotalEvents(0L);
        response.setPredictedAnomalyRate(0.0);
        response.setExpectedAlertVolume(0L);
        response.setHistoricalTotalEvents(normalizeForecastSeries(
                trendStats == null ? null : trendStats.getPayload(), LocalDate.now(), "legacy_trend"));
        response.setHistoricalAnomalyRate(List.of());
        response.setForecastModelNames(Map.of());
        response.setForecastWarnings(List.of("V3.6.1 forecast snapshot not available; returned legacy trend payload when present"));
        return response;
    }

    private Object normalizeForecastSeries(Object raw, LocalDate date, String defaultLabel) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?>) {
            return raw;
        }
        if (raw instanceof Number number) {
            return List.of(forecastPoint(defaultLabel, date, number));
        }
        if (raw instanceof Map<?, ?> map) {
            Object items = map.get("items");
            if (items != null) {
                return normalizeForecastSeries(items, date, defaultLabel);
            }
            if (!map.isEmpty() && map.values().stream().allMatch(value -> value instanceof Map<?, ?>)) {
                return map.entrySet().stream()
                        .map(entry -> forecastPointFromMap(String.valueOf(entry.getKey()), date, (Map<?, ?>) entry.getValue()))
                        .toList();
            }
            Number value = firstNumber(map,
                    "value",
                    "actual",
                    "forecast",
                    "predicted",
                    "actualCount",
                    "actualRate",
                    "count",
                    "rate");
            if (value != null) {
                return List.of(forecastPoint(firstString(map, "label", "seriesKey", "name", defaultLabel), date, value));
            }
        }
        return List.of();
    }

    private Map<String, Object> forecastPointFromMap(String fallbackLabel, LocalDate date, Map<?, ?> map) {
        Number value = firstNumber(map,
                "value",
                "actual",
                "forecast",
                "predicted",
                "actualCount",
                "actualRate",
                "count",
                "rate");
        return forecastPoint(firstString(map, "label", "seriesKey", "name", fallbackLabel), date, value == null ? 0 : value);
    }

    private Map<String, Object> forecastPoint(String label, LocalDate date, Number value) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("label", label);
        if (date != null) {
            point.put("date", date);
        }
        point.put("value", value == null ? 0 : value);
        return point;
    }

    private Number firstNumber(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number;
            }
        }
        return null;
    }

    private String firstString(Map<?, ?> map, String... keys) {
        if (keys.length == 0) {
            return null;
        }
        String fallback = keys[keys.length - 1];
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return fallback;
    }

    private String normalizeRiskLevel(String riskLevel) {
        return riskLevel == null || riskLevel.isBlank() ? "UNKNOWN" : riskLevel.toUpperCase();
    }

    private double nullSafe(Double value) {
        return value == null ? 0.0 : value;
    }
}
