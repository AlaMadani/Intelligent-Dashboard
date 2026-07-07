package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.config.PerformanceProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisPubSubProperties;
import com.noveocare.dataprocessor.dto.NextActionScore;
import com.noveocare.dataprocessor.dto.PathDeviationResult;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.inference.ModelHealthService;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.AnomalyEventRepository;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.noveocare.dataprocessor.entity.AnomalyEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardSnapshotServiceTest {

    private final RedisCacheService redisCacheService = mock(RedisCacheService.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);
    private final ForecastRuntimeService forecastRuntimeService = mock(ForecastRuntimeService.class);
    private final ModelHealthService modelHealthService = mock(ModelHealthService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final StatisticsService statisticsService = mock(StatisticsService.class);
    private final DashboardSnapshotPersistenceService snapshotPersistenceService = mock(DashboardSnapshotPersistenceService.class);
    private final AlertCacheService alertCacheService = mock(AlertCacheService.class);

    private DashboardSnapshotService service;

    @BeforeEach
    void setUp() {
        RedisCacheProperties cacheProperties = new RedisCacheProperties();
        cacheProperties.setSessionInsight(Duration.ofHours(2));

ObjectFactory<ModelHealthService> healthFactory = () -> modelHealthService;
        service = new DashboardSnapshotService(
                redisCacheService,
                cacheProperties,
                sessionAnalysisRepository,
                anomalyEventRepository,
                forecastRuntimeService,
                healthFactory,
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                statisticsService,
                new RedisPubSubProperties(),
                new PerformanceProperties(),
                snapshotPersistenceService,
                alertCacheService
        );
    }

    private void stubSecurityOverviewDefaults() {
        when(redisCacheService.getSetMembers(any())).thenReturn(Set.of());
        when(statisticsService.countEventsForDate(any(LocalDate.class))).thenReturn(100L);
        when(statisticsService.countAlertsForDate(any(LocalDate.class))).thenReturn(10L);
        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(anomalyEventRepository.findByDetectedAtBetween(any(Instant.class), any(Instant.class))).thenReturn(List.of());
    }

    @Test
    void refreshSecurityOverviewReadsForecastFromCache() {
        Map<String, Object> forecastMap = new LinkedHashMap<>();
        forecastMap.put("predictedAnomalyRate", 0.03805);
        forecastMap.put("predictedTotalEvents", 1132.0);
        forecastMap.put("expectedAlertVolume", 43.0);
        when(redisCacheService.getJson(eq(CacheKeys.forecastDashboardV36Key()), any(TypeReference.class))).thenReturn(forecastMap);
        stubSecurityOverviewDefaults();

        service.refreshSecurityOverview();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redisCacheService, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload)
                .containsEntry("predictedAnomalyRateTomorrow", 0.03805)
                .containsEntry("predictedTotalEventsTomorrow", 1132.0)
                .containsEntry("expectedAlertVolumeTomorrow", 43.0);
    }

    @Test
    void refreshSecurityOverviewShowsNullWhenNoForecast() {
        when(redisCacheService.getJson(eq(CacheKeys.forecastDashboardV36Key()), any(TypeReference.class))).thenReturn(null);
        stubSecurityOverviewDefaults();

        service.refreshSecurityOverview();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redisCacheService, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload)
                .containsEntry("predictedAnomalyRateTomorrow", null)
                .containsEntry("predictedTotalEventsTomorrow", null)
                .containsEntry("expectedAlertVolumeTomorrow", null);
        assertThat(payload).containsKey("forecastWarnings");
    }

    @Test
    void topAnomalyTypesPopulatedFromEvents() {
        when(redisCacheService.getJson(eq(CacheKeys.forecastDashboardV36Key()), any(TypeReference.class))).thenReturn(null);
        when(redisCacheService.getSetMembers(any())).thenReturn(Set.of());
        when(statisticsService.countEventsForDate(any(LocalDate.class))).thenReturn(100L);
        when(statisticsService.countAlertsForDate(any(LocalDate.class))).thenReturn(10L);
        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of());

        AnomalyEvent e1 = new AnomalyEvent();
        e1.setAnomalyType("api_scraping");
        AnomalyEvent e2 = new AnomalyEvent();
        e2.setAnomalyType("unknown_suspicious_behavior");
        AnomalyEvent e3 = new AnomalyEvent();
        e3.setAnomalyType("api_scraping");
        AnomalyEvent e4 = new AnomalyEvent();
        e4.setAnomalyType("behavioral_sequence_anomaly");
        when(anomalyEventRepository.findByDetectedAtBetween(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(e1, e2, e3, e4));

        service.refreshSecurityOverview();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redisCacheService, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Long> types = (Map<String, Long>) payload.get("topAnomalyTypes");
        assertThat(types)
                .containsEntry("api_scraping", 2L)
                .containsEntry("unknown_suspicious_behavior", 1L)
                .containsEntry("behavioral_sequence_anomaly", 1L);
        // Must be sorted by count descending
        List<String> keys = List.copyOf(types.keySet());
        assertThat(keys.get(0)).isEqualTo("api_scraping");
    }

    @Test
    void topAnomalyTypesIgnoresNullTypes() {
        when(redisCacheService.getJson(eq(CacheKeys.forecastDashboardV36Key()), any(TypeReference.class))).thenReturn(null);
        when(redisCacheService.getSetMembers(any())).thenReturn(Set.of());
        when(statisticsService.countEventsForDate(any(LocalDate.class))).thenReturn(100L);
        when(statisticsService.countAlertsForDate(any(LocalDate.class))).thenReturn(10L);
        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of());

        AnomalyEvent e1 = new AnomalyEvent();
        e1.setAnomalyType(null);
        AnomalyEvent e2 = new AnomalyEvent();
        e2.setAnomalyType("valid_type");
        when(anomalyEventRepository.findByDetectedAtBetween(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(e1, e2));

        service.refreshSecurityOverview();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redisCacheService, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Long> types = (Map<String, Long>) payload.get("topAnomalyTypes");
        assertThat(types).containsEntry("valid_type", 1L);
        assertThat(types).doesNotContainKey(null);
    }

    @Test
    void topAnomalyTypesLimitedTo10() {
        when(redisCacheService.getJson(eq(CacheKeys.forecastDashboardV36Key()), any(TypeReference.class))).thenReturn(null);
        when(redisCacheService.getSetMembers(any())).thenReturn(Set.of());
        when(statisticsService.countEventsForDate(any(LocalDate.class))).thenReturn(100L);
        when(statisticsService.countAlertsForDate(any(LocalDate.class))).thenReturn(10L);
        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of());

        List<AnomalyEvent> manyEvents = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            AnomalyEvent e = new AnomalyEvent();
            e.setAnomalyType("type_" + i);
            manyEvents.add(e);
        }
        when(anomalyEventRepository.findByDetectedAtBetween(any(Instant.class), any(Instant.class)))
                .thenReturn(manyEvents);

        service.refreshSecurityOverview();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redisCacheService, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Long> types = (Map<String, Long>) payload.get("topAnomalyTypes");
        assertThat(types).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void topAnomalyTypesEmptyWhenNoAlerts() {
        when(redisCacheService.getJson(eq(CacheKeys.forecastDashboardV36Key()), any(TypeReference.class))).thenReturn(null);
        when(redisCacheService.getSetMembers(any())).thenReturn(Set.of());
        when(statisticsService.countEventsForDate(any(LocalDate.class))).thenReturn(0L);
        when(statisticsService.countAlertsForDate(any(LocalDate.class))).thenReturn(0L);
        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(anomalyEventRepository.findByDetectedAtBetween(any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.refreshSecurityOverview();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redisCacheService, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Long> types = (Map<String, Long>) payload.get("topAnomalyTypes");
        assertThat(types).isEmpty();
        assertThat(payload).doesNotContainKey("securityWarnings");
    }

    @Test
    void topAnomalyTypesWarningWhenAlertsExistButNoTypes() {
        when(redisCacheService.getJson(eq(CacheKeys.forecastDashboardV36Key()), any(TypeReference.class))).thenReturn(null);
        when(redisCacheService.getSetMembers(any())).thenReturn(Set.of());
        when(statisticsService.countEventsForDate(any(LocalDate.class))).thenReturn(100L);
        when(statisticsService.countAlertsForDate(any(LocalDate.class))).thenReturn(10L);
        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of());
        // Events exist but all have null anomaly types
        AnomalyEvent e1 = new AnomalyEvent();
        e1.setAnomalyType(null);
        AnomalyEvent e2 = new AnomalyEvent();
        e2.setAnomalyType(null);
        when(anomalyEventRepository.findByDetectedAtBetween(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(e1, e2));

        service.refreshSecurityOverview();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redisCacheService, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();
        @SuppressWarnings("unchecked")
        Map<String, Long> types = (Map<String, Long>) payload.get("topAnomalyTypes");
        assertThat(types).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> warnings = (Map<String, Object>) payload.get("securityWarnings");
        assertThat(warnings).containsKey("top_anomaly_types_empty_despite_alerts");
    }

    @Test
    void cacheSessionInsightPublishesExpandedWorkerContract() {
        SessionSummary summary = SessionSummary.builder()
                .insuredId("insured-1")
                .sessionId("session-1")
                .month("APRIL")
                .sessionNumber(3)
                .sessionStart(Instant.parse("2026-04-27T09:00:00Z"))
                .sessionEnd(Instant.parse("2026-04-27T09:10:00Z"))
                .totalEvents(12)
                .totalDurationSeconds(600L)
                .uniqueActions(5)
                .uniqueRoutes(4)
                .uniqueIpsUsed(2)
                .uniqueDevicesUsed(1)
                .totalKOs(2)
                .totalOKs(10)
                .longestKoStreak(2)
                .hasLogin(1)
                .hasLogout(0)
                .ipChanged(1)
                .deviceChanged(0)
                .totalDownloadActions(3)
                .maxDownloadsIn2Minutes(2)
                .pingPongCount(1)
                .riskScoreMax(87.0)
                .riskScoreAvg(45.0)
                .anomalyEventCount(1)
                .anomalyTypes(List.of("geo_jump"))
                .campaignIds(List.of("campaign-1"))
                .actionCounts(Map.of("LOGIN", 1L, "DOWNLOAD", 3L))
                .actionSequenceSignature("sig-a")
                .routeSequenceSignature("sig-r")
                .build();
        SessionInsight insight = SessionInsight.builder()
                .binaryAnomaly(true)
                .anomaly(true)
                .anomalyScore(0.91)
                .anomalyProbability(0.88)
                .binaryDetectorArtifact("transformer_sequence_engine.onnx")
                .anomalyType("geo_jump")
                .anomalyTypeSource("heuristic")
                .anomalyTypeConfidence(0.83)
                .churnProbability(0.12)
                .aiRiskScore(72.0)
                .ruleRiskScore(55.0)
                .finalRiskScore(66.05)
                .ensembleRiskScore(77.0)
                .personaCluster(2)
                .personaLabel("regular_user")
                .personaSource("kmeans")
                .riskLevel("HIGH")
                .pathDeviation(PathDeviationResult.builder()
                        .deviated(true)
                        .fromAction("LOGIN")
                        .toAction("DOWNLOAD")
                        .transitionProbability(0.01)
                        .build())
                .nextActions(List.of(NextActionScore.builder().action("VERIFY").probability(0.75).build()))
                .computedAt(Instant.parse("2026-04-27T09:05:00Z"))
                .build();

        service.cacheSessionInsight(summary, insight);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisCacheService, atLeastOnce()).setJson(anyString(), payloadCaptor.capture(), any(Duration.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getAllValues().get(0);
        assertThat(payload)
                .containsEntry("binaryDetectorArtifact", "transformer_sequence_engine.onnx")
                .containsEntry("typeConfidence", 0.83)
                .containsEntry("ensembleRiskScore", 66.05)
                .containsEntry("finalRiskScore", 66.05)
                .containsEntry("aiRiskScore", 72.0)
                .containsEntry("ruleRiskScore", 55.0)
                .containsEntry("personaLabel", "regular_user")
                .containsEntry("pathDeviationFlag", true)
                .containsEntry("transitionFromAction", "LOGIN")
                .containsEntry("totalKOs", 2)
                .containsEntry("totalDownloadActions", 3);
    }
}
