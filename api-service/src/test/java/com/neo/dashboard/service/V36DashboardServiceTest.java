package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36ChurnDashboardResponse;
import com.neo.dashboard.dto.v36.V36DiagnosticsResponse;
import com.neo.dashboard.dto.v36.V36RuntimeHealthResponse;
import com.neo.dashboard.dto.v36.V36SecurityOverviewResponse;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V36DashboardServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final StatsService statsService = mock(StatsService.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
    private final DashboardSnapshotFallbackService snapshotFallbackService = mock(DashboardSnapshotFallbackService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final V36User360Service user360Service = mock(V36User360Service.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);

    private V36DashboardService service;

    @BeforeEach
    void setUp() {
        service = new V36DashboardService(
                redisReadService,
                statsService,
                sessionAnalysisRepository,
                new ObjectMapper().findAndRegisterModules(),
                snapshotFallbackService,
                redisTemplate,
                user360Service,
                anomalyEventRepository
        );
    }

    /**
     * Runtime Health Reads V36Redis Snapshot
     */
    @Test
    void runtimeHealthReadsV36RedisSnapshot() {
        V36RuntimeHealthResponse response = new V36RuntimeHealthResponse();
        response.setStatus("HEALTHY");
        response.setSessionFinalization(Map.of(
                "openSessionCount", 5,
                "sessionsFinalizedByExplicitEnd", 10,
                "sessionsFinalizedByInactivityTimeout", 3,
                "sessionsFinalizedByMaxDuration", 1,
                "lateEventsForFinalizedSessions", 0
        ));
        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(response, "redis", null, null));

        V36RuntimeHealthResponse result = service.getRuntimeHealth();

        assertThat(result.getSchemaVersion()).isEqualTo("v3.6.1");
        assertThat(result.getStatus()).isEqualTo("HEALTHY");
        assertThat(result.getSessionFinalization()).isNotNull();
        assertThat(result.getSessionFinalization()).containsEntry("openSessionCount", 5);
        assertThat(result.getSessionFinalization()).containsEntry("sessionsFinalizedByExplicitEnd", 10);
    }

    /**
     * Runtime Health Reads V36Redis Snapshot Without Session Finalization
     */
    @Test
    void runtimeHealthReadsV36RedisSnapshotWithoutSessionFinalization() {
        V36RuntimeHealthResponse response = new V36RuntimeHealthResponse();
        response.setStatus("HEALTHY");
        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(response, "redis", null, null));

        V36RuntimeHealthResponse result = service.getRuntimeHealth();

        assertThat(result.getSchemaVersion()).isEqualTo("v3.6.1");
        assertThat(result.getStatus()).isEqualTo("HEALTHY");
        assertThat(result.getSessionFinalization()).isNull();
    }

    /**
     * Runtime Health Falls Back To Unknown When Redis Missing
     */
    @Test
    void runtimeHealthFallsBackToUnknownWhenRedisMissing() {
        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(null);

        V36RuntimeHealthResponse result = service.getRuntimeHealth();

        assertThat(result.getStatus()).isEqualTo("UNKNOWN");
        assertThat(result.getMessage()).contains("not available");
        assertThat(result.getSource()).isEqualTo("generated_fallback");
    }

    /**
     * Diagnostics Includes Session Finalization When Available
     */
    @Test
    void diagnosticsIncludesSessionFinalizationWhenAvailable() {
        V36RuntimeHealthResponse runtimeHealth = new V36RuntimeHealthResponse();
        runtimeHealth.setStatus("HEALTHY");
        runtimeHealth.setFallbackMode("normal");
        runtimeHealth.setSessionFinalization(Map.of(
                "openSessionCount", 3,
                "sessionsFinalizedByExplicitEnd", 128,
                "sessionsFinalizedByInactivityTimeout", 31,
                "expiredSessionFlushLastRunAt", "2026-05-25T02:35:00Z",
                "expiredSessionFlushLastFinalizedCount", 2
        ));
        runtimeHealth.setWarnings(List.of());

        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(runtimeHealth, "redis", null, null));
        when(redisReadService.readJson(CacheKeys.AI_SEQUENCE_FIELD_COVERAGE_V36))
                .thenReturn(Optional.empty());
        when(redisReadService.readJson(CacheKeys.AI_TABULAR_FIELD_COVERAGE_V36))
                .thenReturn(Optional.empty());
        when(redisReadService.readJson(CacheKeys.AI_MODEL_LATENCY_V36))
                .thenReturn(Optional.empty());

        V36DiagnosticsResponse diagnostics = service.getDiagnostics();

        assertThat(diagnostics.getRuntimeHealth()).isNotNull();
        assertThat(diagnostics.getRuntimeHealth().getSessionFinalization()).isNotNull();
        assertThat(diagnostics.getRuntimeHealth().getSessionFinalization())
                .containsEntry("openSessionCount", 3);
        assertThat(diagnostics.getRuntimeHealth().getSessionFinalization())
                .containsEntry("sessionsFinalizedByExplicitEnd", 128);
    }

    /**
     * Diagnostics Works When Session Finalization Missing
     */
    @Test
    void diagnosticsWorksWhenSessionFinalizationMissing() {
        V36RuntimeHealthResponse runtimeHealth = new V36RuntimeHealthResponse();
        runtimeHealth.setStatus("HEALTHY");
        runtimeHealth.setFallbackMode("normal");
        runtimeHealth.setWarnings(List.of());

        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(runtimeHealth, "redis", null, null));
        when(redisReadService.readJson(CacheKeys.AI_SEQUENCE_FIELD_COVERAGE_V36))
                .thenReturn(Optional.empty());
        when(redisReadService.readJson(CacheKeys.AI_TABULAR_FIELD_COVERAGE_V36))
                .thenReturn(Optional.empty());
        when(redisReadService.readJson(CacheKeys.AI_MODEL_LATENCY_V36))
                .thenReturn(Optional.empty());

        V36DiagnosticsResponse diagnostics = service.getDiagnostics();

        assertThat(diagnostics.getRuntimeHealth()).isNotNull();
        assertThat(diagnostics.getRuntimeHealth().getSessionFinalization()).isNull();
    }

    /**
     * Runtime Health Includes New Kafka Section
     */
    @Test
    void runtimeHealthIncludesNewKafkaSection() {
        Map<String, Object> kafka = new LinkedHashMap<>();
        kafka.put("consumerGroupId", "dp-group");
        kafka.put("topic", "audit-events");
        kafka.put("configuredConcurrency", 3);
        kafka.put("recordsProcessedTotal", 1542000);
        kafka.put("processingFailuresTotal", 12);

        V36RuntimeHealthResponse response = new V36RuntimeHealthResponse();
        response.setStatus("HEALTHY");
        response.setKafka(kafka);
        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(response, "redis", null, null));

        V36RuntimeHealthResponse result = service.getRuntimeHealth();

        assertThat(result.getKafka()).isNotNull();
        assertThat(result.getKafka()).containsEntry("consumerGroupId", "dp-group");
        assertThat(result.getKafka()).containsEntry("topic", "audit-events");
        assertThat(result.getKafka()).containsEntry("recordsProcessedTotal", 1542000);
    }

    /**
     * Runtime Health Includes New Idempotency Section
     */
    @Test
    void runtimeHealthIncludesNewIdempotencySection() {
        Map<String, Object> idempotency = new LinkedHashMap<>();
        idempotency.put("duplicateEventsSkipped", 34);
        idempotency.put("duplicateAlertsSkipped", 2);
        idempotency.put("duplicateSqlWritesSkipped", 7);

        V36RuntimeHealthResponse response = new V36RuntimeHealthResponse();
        response.setStatus("HEALTHY");
        response.setIdempotency(idempotency);
        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(response, "redis", null, null));

        V36RuntimeHealthResponse result = service.getRuntimeHealth();

        assertThat(result.getIdempotency()).isNotNull();
        assertThat(result.getIdempotency()).containsEntry("duplicateEventsSkipped", 34);
    }

    /**
     * Runtime Health Includes New Performance Section
     */
    @Test
    void runtimeHealthIncludesNewPerformanceSection() {
        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("eventProcessingMsAvg", 45.2);
        performance.put("recordsProcessedPerSecond", 320.0);
        performance.put("loadSheddingMode", "disabled");

        V36RuntimeHealthResponse response = new V36RuntimeHealthResponse();
        response.setStatus("HEALTHY");
        response.setPerformance(performance);
        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(response, "redis", null, null));

        V36RuntimeHealthResponse result = service.getRuntimeHealth();

        assertThat(result.getPerformance()).isNotNull();
        assertThat(result.getPerformance()).containsEntry("eventProcessingMsAvg", 45.2);
        assertThat(result.getPerformance()).containsEntry("loadSheddingMode", "disabled");
    }

    /**
     * Runtime Health Remains Compatible When New Sections Absent
     */
    @Test
    void runtimeHealthRemainsCompatibleWhenNewSectionsAbsent() {
        V36RuntimeHealthResponse response = new V36RuntimeHealthResponse();
        response.setStatus("HEALTHY");
        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(response, "redis", null, null));

        V36RuntimeHealthResponse result = service.getRuntimeHealth();

        assertThat(result.getKafka()).isNull();
        assertThat(result.getIdempotency()).isNull();
        assertThat(result.getPerformance()).isNull();
        assertThat(result.getStats()).isNull();
        assertThat(result.getNextActionPrediction()).isNull();
    }

    /**
     * Diagnostics Preserves New Runtime Sections
     */
    @Test
    void diagnosticsPreservesNewRuntimeSections() {
        Map<String, Object> kafka = Map.of("consumerGroupId", "dp-group", "topic", "audit-events");
        Map<String, Object> idempotency = Map.of("duplicateEventsSkipped", 34);
        Map<String, Object> performance = Map.of("eventProcessingMsAvg", 45.2);
        Map<String, Object> stats = Map.of("liveTimeBasis", "ingestion");
        Map<String, Object> sessionFinalization = Map.of("openSessionCount", 5, "sessionsFinalizedByExplicitEnd", 10);

        V36RuntimeHealthResponse runtimeHealth = new V36RuntimeHealthResponse();
        runtimeHealth.setStatus("HEALTHY");
        runtimeHealth.setFallbackMode("normal");
        runtimeHealth.setWarnings(List.of());
        runtimeHealth.setKafka(kafka);
        runtimeHealth.setIdempotency(idempotency);
        runtimeHealth.setPerformance(performance);
        runtimeHealth.setStats(stats);
        runtimeHealth.setSessionFinalization(sessionFinalization);

        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.AI_RUNTIME_HEALTH_V36),
                eq(V36RuntimeHealthResponse.class),
                eq("model-health"),
                eq("model-health:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(runtimeHealth, "redis", null, null));
        when(redisReadService.readJson(CacheKeys.AI_SEQUENCE_FIELD_COVERAGE_V36))
                .thenReturn(Optional.empty());
        when(redisReadService.readJson(CacheKeys.AI_TABULAR_FIELD_COVERAGE_V36))
                .thenReturn(Optional.empty());
        when(redisReadService.readJson(CacheKeys.AI_MODEL_LATENCY_V36))
                .thenReturn(Optional.empty());

        V36DiagnosticsResponse diagnostics = service.getDiagnostics();

        assertThat(diagnostics.getRuntimeHealth()).isNotNull();
        assertThat(diagnostics.getRuntimeHealth().getKafka()).isNotNull();
        assertThat(diagnostics.getRuntimeHealth().getKafka()).containsEntry("consumerGroupId", "dp-group");
        assertThat(diagnostics.getRuntimeHealth().getIdempotency()).isNotNull();
        assertThat(diagnostics.getRuntimeHealth().getPerformance()).isNotNull();
        assertThat(diagnostics.getRuntimeHealth().getStats()).isNotNull();
        assertThat(diagnostics.getRuntimeHealth().getSessionFinalization()).isNotNull();
        assertThat(diagnostics.getRuntimeHealth().getSessionFinalization()).containsEntry("openSessionCount", 5);

        assertThat(diagnostics.getKafka()).isNotNull();
        assertThat(diagnostics.getKafka()).containsEntry("consumerGroupId", "dp-group");
        assertThat(diagnostics.getIdempotency()).isNotNull();
        assertThat(diagnostics.getPerformance()).isNotNull();
        assertThat(diagnostics.getStats()).isNotNull();
        assertThat(diagnostics.getSessionFinalization()).isNotNull();
        assertThat(diagnostics.getSessionFinalization()).containsEntry("openSessionCount", 5);
    }

    /**
     * Security Overview Redis Hit Returns Redis Source
     */
    @Test
    void securityOverviewRedisHitReturnsRedisSource() {
        V36SecurityOverviewResponse security = new V36SecurityOverviewResponse();
        security.setTotalEventsToday(100L);
        security.setActiveUsersToday(25L);
        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36),
                eq(V36SecurityOverviewResponse.class),
                eq("security-overview"),
                eq("security-overview:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(security, "redis", null, null));

        V36SecurityOverviewResponse result = service.getSecurityOverview();

        assertThat(result.getTotalEventsToday()).isEqualTo(100L);
        assertThat(result.getSource()).isEqualTo("redis");
    }

    /**
     * Security Overview Both Miss Returns Generated Fallback
     */
    @Test
    void securityOverviewBothMissReturnsGeneratedFallback() {
        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36),
                eq(V36SecurityOverviewResponse.class),
                eq("security-overview"),
                eq("security-overview:latest")))
                .thenReturn(null);

        V36SecurityOverviewResponse result = service.getSecurityOverview();

        assertThat(result.getTotalEventsToday()).isZero();
        assertThat(result.getSource()).isEqualTo("generated_fallback");
    }

    /**
     * Security Overview Populates Top Anomaly Types From Sql When Alerts Exist
     */
    @Test
    void securityOverviewPopulatesTopAnomalyTypesFromSqlWhenAlertsExist() {
        V36SecurityOverviewResponse overview = new V36SecurityOverviewResponse();
        overview.setHighRiskAlertsToday(5L);
        overview.setTopAnomalyTypes(null);

        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36),
                eq(V36SecurityOverviewResponse.class),
                eq("security-overview"),
                eq("security-overview:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(overview, "redis", null, null));

        when(anomalyEventRepository.countByAnomalyTypeSince(any(Instant.class)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"api_scraping", 2L},
                        new Object[]{"unknown_suspicious_behavior", 2L},
                        new Object[]{"behavioral_sequence_anomaly", 1L}
                ));

        V36SecurityOverviewResponse result = service.getSecurityOverview();

        assertThat(result.getTopAnomalyTypes())
                .containsEntry("api_scraping", 2L)
                .containsEntry("unknown_suspicious_behavior", 2L)
                .containsEntry("behavioral_sequence_anomaly", 1L);
    }

    /**
     * Security Overview Ignores Null Anomaly Types
     */
    @Test
    void securityOverviewIgnoresNullAnomalyTypes() {
        V36SecurityOverviewResponse overview = new V36SecurityOverviewResponse();
        overview.setHighRiskAlertsToday(3L);
        overview.setTopAnomalyTypes(null);

        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36),
                eq(V36SecurityOverviewResponse.class),
                eq("security-overview"),
                eq("security-overview:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(overview, "redis", null, null));

        when(anomalyEventRepository.countByAnomalyTypeSince(any(Instant.class)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"api_scraping", 3L},
                        new Object[]{null, 1L}
                ));

        V36SecurityOverviewResponse result = service.getSecurityOverview();

        assertThat(result.getTopAnomalyTypes()).containsExactly(Map.entry("api_scraping", 3L));
    }

    /**
     * Security Overview Counts Unknown Suspicious Behavior
     */
    @Test
    void securityOverviewCountsUnknownSuspiciousBehavior() {
        V36SecurityOverviewResponse overview = new V36SecurityOverviewResponse();
        overview.setHighRiskAlertsToday(1L);
        overview.setTopAnomalyTypes(null);

        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36),
                eq(V36SecurityOverviewResponse.class),
                eq("security-overview"),
                eq("security-overview:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(overview, "redis", null, null));

        when(anomalyEventRepository.countByAnomalyTypeSince(any(Instant.class)))
                .thenReturn(List.<Object[]>of(new Object[]{"unknown_suspicious_behavior", 1L}));

        V36SecurityOverviewResponse result = service.getSecurityOverview();

        assertThat(result.getTopAnomalyTypes()).containsEntry("unknown_suspicious_behavior", 1L);
    }

    /**
     * Security Overview Top Anomaly Types Sorted By Count Desc
     */
    @Test
    void securityOverviewTopAnomalyTypesSortedByCountDesc() {
        V36SecurityOverviewResponse overview = new V36SecurityOverviewResponse();
        overview.setHighRiskAlertsToday(10L);
        overview.setTopAnomalyTypes(null);

        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36),
                eq(V36SecurityOverviewResponse.class),
                eq("security-overview"),
                eq("security-overview:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(overview, "redis", null, null));

        when(anomalyEventRepository.countByAnomalyTypeSince(any(Instant.class)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"low", 1L},
                        new Object[]{"high", 10L},
                        new Object[]{"medium", 5L}
                ));

        V36SecurityOverviewResponse result = service.getSecurityOverview();

        assertThat(result.getTopAnomalyTypes()).containsExactly(
                Map.entry("high", 10L),
                Map.entry("medium", 5L),
                Map.entry("low", 1L)
        );
    }

    /**
     * Security Overview Top Anomaly Types Empty When No Alerts
     */
    @Test
    void securityOverviewTopAnomalyTypesEmptyWhenNoAlerts() {
        V36SecurityOverviewResponse overview = new V36SecurityOverviewResponse();
        overview.setHighRiskAlertsToday(0L);
        overview.setCriticalAlertsToday(0L);
        overview.setTopAnomalyTypes(Map.of());

        when(snapshotFallbackService.readWithFallback(
                eq(CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36),
                eq(V36SecurityOverviewResponse.class),
                eq("security-overview"),
                eq("security-overview:latest")))
                .thenReturn(new DashboardSnapshotFallbackService.FallbackResult<>(overview, "redis", null, null));

        V36SecurityOverviewResponse result = service.getSecurityOverview();

        assertThat(result.getTopAnomalyTypes()).isEmpty();
    }

    /* ------------------------------------------------------------------ */
    /*  Churn Dashboard fallback enrichment tests                          */
    /* ------------------------------------------------------------------ */

    /**
     * Churn Fallback Deduplicates Top Users
     */
    @Test
    void churnFallbackDeduplicatesTopUsers() {
        Instant now = Instant.now();
        SessionAnalysis a1 = churnSession("insured-A", "sess-1", 0.86, "HIGH", now.minusSeconds(10));
        SessionAnalysis a2 = churnSession("insured-A", "sess-2", 0.84, "HIGH", now.minusSeconds(20));
        SessionAnalysis b1 = churnSession("insured-B", "sess-3", 0.83, "HIGH", now.minusSeconds(30));

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(a1, a2, b1));
        when(snapshotFallbackService.readWithFallback(any(), any(), any(), any())).thenReturn(null);
        when(user360Service.pickWinner(any())).thenCallRealMethod();
        when(user360Service.computeUserRiskSummaryLast30d(anyString())).thenReturn(emptyRiskSummary());

        V36ChurnDashboardResponse result = service.getChurnDashboard();

        assertThat(result.getSource()).isEqualTo("generated_fallback");
        assertThat(result.getTopChurnRiskUsers()).hasSize(2);
        List<String> insuredIds = result.getTopChurnRiskUsers().stream()
                .map(m -> (String) m.get("insuredId"))
                .toList();
        assertThat(insuredIds).containsExactly("insured-A", "insured-B");
    }

    /**
     * Churn Fallback Top Users Include Risk Fields
     */
    @Test
    void churnFallbackTopUsersIncludeRiskFields() {
        Instant now = Instant.now();
        SessionAnalysis session = churnSession("insured-A", "sess-1", 0.90, "HIGH", now);

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(session));
        when(snapshotFallbackService.readWithFallback(any(), any(), any(), any())).thenReturn(null);
        when(user360Service.pickWinner(any())).thenCallRealMethod();

        Map<String, Object> riskSummary = new LinkedHashMap<>();
        riskSummary.put("averageRiskScoreLast30d", 66.6);
        riskSummary.put("alertCountLast30d", 5);
        riskSummary.put("criticalAlertCountLast30d", 0);
        when(user360Service.computeUserRiskSummaryLast30d("insured-A")).thenReturn(riskSummary);

        V36ChurnDashboardResponse result = service.getChurnDashboard();

        assertThat(result.getTopChurnRiskUsers()).hasSize(1);
        Map<String, Object> user = result.getTopChurnRiskUsers().get(0);
        assertThat(user).containsEntry("insuredId", "insured-A");
        assertThat(user).containsEntry("averageRiskScoreLast30d", 66.6);
        assertThat(user).containsEntry("alertCountLast30d", 5);
        assertThat(user).containsEntry("criticalAlertCountLast30d", 0);
        assertThat(user).containsEntry("latestFinalRiskScore", 88.0);
        assertThat(user).containsEntry("latestRiskLevel", "HIGH");
    }

    /**
     * Churn Fallback Top Users Sorted By Probability Desc
     */
    @Test
    void churnFallbackTopUsersSortedByProbabilityDesc() {
        Instant now = Instant.now();
        SessionAnalysis low = churnSession("insured-C", "sess-c", 0.70, "MEDIUM", now);
        SessionAnalysis high = churnSession("insured-A", "sess-a", 0.95, "HIGH", now);
        SessionAnalysis mid = churnSession("insured-B", "sess-b", 0.85, "HIGH", now);

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(low, high, mid));
        when(snapshotFallbackService.readWithFallback(any(), any(), any(), any())).thenReturn(null);
        when(user360Service.pickWinner(any())).thenCallRealMethod();
        when(user360Service.computeUserRiskSummaryLast30d(anyString())).thenReturn(emptyRiskSummary());

        V36ChurnDashboardResponse result = service.getChurnDashboard();

        assertThat(result.getTopChurnRiskUsers()).hasSize(3);
        assertThat(result.getTopChurnRiskUsers().get(0).get("insuredId")).isEqualTo("insured-A");
        assertThat(result.getTopChurnRiskUsers().get(1).get("insuredId")).isEqualTo("insured-B");
        assertThat(result.getTopChurnRiskUsers().get(2).get("insuredId")).isEqualTo("insured-C");
    }

    /**
     * Churn Fallback Limits Top Users To10
     */
    @Test
    void churnFallbackLimitsTopUsersTo10() {
        Instant now = Instant.now();
        List<SessionAnalysis> sessions = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> churnSession("insured-" + i, "sess-" + i, 0.90 - i * 0.01, "HIGH", now))
                .toList();

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(sessions);
        when(snapshotFallbackService.readWithFallback(any(), any(), any(), any())).thenReturn(null);
        when(user360Service.pickWinner(any())).thenCallRealMethod();
        when(user360Service.computeUserRiskSummaryLast30d(anyString())).thenReturn(emptyRiskSummary());

        V36ChurnDashboardResponse result = service.getChurnDashboard();

        assertThat(result.getTopChurnRiskUsers()).hasSize(10);
    }

    private static SessionAnalysis churnSession(String insuredId, String sessionId,
                                                  Double churnProbability, String churnRiskLevel,
                                                  Instant endTime) {
        SessionAnalysis s = new SessionAnalysis();
        s.setInsuredId(insuredId);
        s.setSessionId(sessionId);
        s.setChurnProbability(churnProbability);
        s.setChurnRiskLevel(churnRiskLevel);
        s.setFinalRiskScore(88.0);
        s.setRiskLevel("HIGH");
        s.setEndTime(endTime);
        s.setCreatedAt(endTime);
        return s;
    }

    private static Map<String, Object> emptyRiskSummary() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("averageRiskScoreLast30d", null);
        r.put("alertCountLast30d", 0);
        r.put("criticalAlertCountLast30d", 0);
        return r;
    }
}
