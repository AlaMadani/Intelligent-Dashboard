package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36DiagnosticsResponse;
import com.neo.dashboard.dto.v36.V36RuntimeHealthResponse;
import com.neo.dashboard.dto.v36.V36SecurityOverviewResponse;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V36DashboardServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final StatsService statsService = mock(StatsService.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
    private final DashboardSnapshotFallbackService snapshotFallbackService = mock(DashboardSnapshotFallbackService.class);

    private V36DashboardService service;

    @BeforeEach
    void setUp() {
        service = new V36DashboardService(
                redisReadService,
                statsService,
                sessionAnalysisRepository,
                new ObjectMapper().findAndRegisterModules(),
                snapshotFallbackService
        );
    }

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
}
