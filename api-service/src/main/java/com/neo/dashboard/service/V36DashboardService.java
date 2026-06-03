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
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
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

    public V36RuntimeHealthResponse getRuntimeHealth() {
        return redisReadService.readValue(CacheKeys.AI_RUNTIME_HEALTH_V36, V36RuntimeHealthResponse.class)
                .map(this::normalizeRuntimeHealth)
                .orElseGet(V36RuntimeHealthResponse::unknown);
    }

    public V36SecurityOverviewResponse getSecurityOverview() {
        return redisReadService.readValue(CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36, V36SecurityOverviewResponse.class)
                .map(this::normalizeSecurityOverview)
                .orElseGet(this::buildSecurityOverviewFallback);
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

        return new V36DiagnosticsResponse(
                CacheKeys.V36_SCHEMA_VERSION,
                runtimeHealth,
                fieldCoverage,
                modelLatency.orElse(null),
                runtimeHealth.getFallbackMode(),
                warnings.stream().distinct().toList()
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
        return redisReadService.readValue(CacheKeys.DASHBOARD_CHURN_V36, V36ChurnDashboardResponse.class)
                .map(this::normalizeChurnDashboard)
                .orElseGet(this::buildChurnFallback);
    }

    public V36ForecastDashboardResponse getForecastDashboard() {
        return redisReadService.readValue(CacheKeys.DASHBOARD_FORECAST_V36, V36ForecastDashboardResponse.class)
                .map(this::normalizeForecastDashboard)
                .orElseGet(this::buildForecastFallback);
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
                    return item;
                })
                .toList();

        return new V36ChurnDashboardResponse(
                CacheKeys.V36_SCHEMA_VERSION,
                withChurn.size(),
                distribution.getOrDefault("HIGH", 0L),
                distribution.getOrDefault("MEDIUM", 0L),
                distribution.getOrDefault("LOW", 0L),
                average,
                topUsers,
                distribution
        );
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

    private V36ForecastDashboardResponse buildForecastFallback() {
        StatsResponseDto trendStats = statsService.getTrendStats(LocalDate.now());
        return new V36ForecastDashboardResponse(
                CacheKeys.V36_SCHEMA_VERSION,
                LocalDate.now().plusDays(1),
                0L,
                0.0,
                0L,
                normalizeForecastSeries(trendStats == null ? null : trendStats.getPayload(), LocalDate.now(), "legacy_trend"),
                List.of(),
                Map.of(),
                List.of("V3.6.1 forecast snapshot not available; returned legacy trend payload when present")
        );
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
