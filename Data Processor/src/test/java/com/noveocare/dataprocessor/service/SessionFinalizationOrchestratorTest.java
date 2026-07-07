package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import com.noveocare.dataprocessor.config.PerformanceProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.kafka.AlertPublisher;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionFinalizationOrchestratorTest {

    @Mock
    private AlertPublisher alertPublisher;
    @Mock
    private SessionAnalysisRepository sessionAnalysisRepository;
    @Mock
    private StatisticsService statisticsService;
    @Mock
    private DashboardSnapshotService dashboardSnapshotService;
    @Mock
    private RedisSessionBufferService sessionBufferService;
    @Mock
    private RedisCacheService redisCacheService;
    @Mock
    private SessionFinalizationService finalizationService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private AlertCacheService alertCacheService;

    private FeatureEngineeringProperties featureProperties;
    private RedisCacheProperties redisCacheProperties;
    private SessionFinalizationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        featureProperties = new FeatureEngineeringProperties();
        featureProperties.setSessionAlertRiskThreshold(60.0);
        redisCacheProperties = new RedisCacheProperties();

        lenient().when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        lenient().when(sessionAnalysisRepository.save(any())).thenReturn(null);
        lenient().when(redisCacheService.hasKey(anyString())).thenReturn(false);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(setOperations.add(anyString(), anyString())).thenReturn(1L);

        orchestrator = new SessionFinalizationOrchestrator(
                new ObjectMapper(), alertPublisher, sessionAnalysisRepository,
                statisticsService, dashboardSnapshotService,
                sessionBufferService, redisCacheService,
                redisCacheProperties, featureProperties,
                finalizationService, new PerformanceProperties(), alertCacheService);
    }

    @Test
    void alertEventIdMatchesPayloadEventId() {
        SessionInsight insight = SessionInsight.builder()
                .insuredId("insured-1")
                .sessionId("sess-1")
                .computedAt(Instant.now())
                .anomaly(true)
                .anomalyScore(80.0)
                .anomalyProbability(0.8)
                .finalRiskScore(80.0)
                .riskLevel("CRITICAL")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .triggeredRules(List.of())
                .llmExplanationEvidencePayload(Map.of(
                        "eventId", "evt-x",
                        "evidenceHash", "abc123",
                        "evidenceVersion", "v1"
                ))
                .build();

        List<AuditTrailEvent> events = List.of(createEvent("evt-x", "login"));

        orchestrator.completeFinalization(summary(), insight, events, List.of(), "test", false);

        ArgumentCaptor<AnomalyAlert> alertCaptor = ArgumentCaptor.forClass(AnomalyAlert.class);
        verify(alertPublisher, atLeastOnce()).publish(alertCaptor.capture(), anyString());
        AnomalyAlert alert = alertCaptor.getValue();

        assertThat(alert.getEventId()).isEqualTo("evt-x");
        assertThat(alert.getLlmEvidencePayloadAvailable()).isTrue();
        assertThat(alert.getLlmEvidencePayloadRedisKey()).isEqualTo(CacheKeys.alertLlmEvidenceKey("evt-x"));

        verify(redisCacheService).setJson(eq(CacheKeys.alertLlmEvidenceKey("evt-x")), any(), any());

        assertThat(orchestrator.getLlmEvidenceKeyPayloadMismatchTotal()).isZero();
    }

    @Test
    void alertEventIdDiffersFromPayloadEventId() {
        SessionInsight insight = SessionInsight.builder()
                .insuredId("insured-1")
                .sessionId("sess-1")
                .computedAt(Instant.now())
                .anomaly(true)
                .anomalyScore(80.0)
                .anomalyProbability(0.8)
                .finalRiskScore(80.0)
                .riskLevel("CRITICAL")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .triggeredRules(List.of())
                .llmExplanationEvidencePayload(Map.of(
                        "eventId", "evt-y",
                        "evidenceHash", "def456",
                        "evidenceVersion", "v2"
                ))
                .build();

        List<AuditTrailEvent> events = List.of(createEvent("evt-x", "login"));

        orchestrator.completeFinalization(summary(), insight, events, List.of(), "test", false);

        ArgumentCaptor<AnomalyAlert> alertCaptor = ArgumentCaptor.forClass(AnomalyAlert.class);
        verify(alertPublisher, atLeastOnce()).publish(alertCaptor.capture(), anyString());
        AnomalyAlert alert = alertCaptor.getValue();

        assertThat(alert.getEventId()).isEqualTo("evt-x");
        assertThat(alert.getLlmEvidencePayloadAvailable()).isFalse();
        assertThat(alert.getLlmEvidencePayloadRedisKey()).isNull();

        verify(redisCacheService, never()).setJson(eq(CacheKeys.alertLlmEvidenceKey("evt-x")), any(), any());
        verify(redisCacheService, never()).setJson(eq(CacheKeys.alertLlmEvidenceKey("evt-y")), any(), any());

        assertThat(orchestrator.getLlmEvidenceKeyPayloadMismatchTotal()).isOne();
    }

    @Test
    void explicitLogoutLastEventYEvidenceWrittenUnderYOnly() {
        Map<String, Object> payloadForX = Map.of(
                "eventId", "evt-x",
                "evidenceHash", "hash-x",
                "evidenceVersion", "v1"
        );

        SessionInsight insight = SessionInsight.builder()
                .insuredId("insured-1")
                .sessionId("sess-1")
                .computedAt(Instant.now())
                .anomaly(true)
                .anomalyScore(80.0)
                .anomalyProbability(0.8)
                .finalRiskScore(80.0)
                .riskLevel("CRITICAL")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .triggeredRules(List.of())
                .llmExplanationEvidencePayload(new LinkedHashMap<>(payloadForX))
                .build();

        List<AuditTrailEvent> fullHistory = List.of(
                createEvent("evt-x", "search"),
                createEvent("evt-y", "logout")
        );

        List<String> triggeredRules = List.of();
        String endReason = "logout";

        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        when(redisCacheService.hasKey(anyString())).thenReturn(false);

        orchestrator.completeFinalization(summary(), insight, fullHistory, triggeredRules, endReason, true);

        ArgumentCaptor<AnomalyAlert> alertCaptor = ArgumentCaptor.forClass(AnomalyAlert.class);
        verify(alertPublisher, atLeastOnce()).publish(alertCaptor.capture(), anyString());
        AnomalyAlert alert = alertCaptor.getValue();

        assertThat(alert.getEventId()).isEqualTo("evt-y");
        assertThat(alert.getLlmEvidencePayloadAvailable()).isFalse();
        assertThat(alert.getLlmEvidencePayloadRedisKey()).isNull();

        verify(redisCacheService, never()).setJson(eq(CacheKeys.alertLlmEvidenceKey("evt-x")), any(), any());
        verify(redisCacheService, never()).setJson(eq(CacheKeys.alertLlmEvidenceKey("evt-y")), any(), any());

        assertThat(orchestrator.getLlmEvidenceKeyPayloadMismatchTotal()).isOne();
    }

    private SessionSummary summary() {
        return SessionSummary.builder()
                .insuredId("insured-1")
                .sessionId("sess-1")
                .totalEvents(5)
                .totalDurationSeconds(300L)
                .build();
    }

    private AuditTrailEvent createEvent(String id, String action) {
        AuditTrailEvent evt = new AuditTrailEvent();
        evt.setId(id);
        evt.setCreatedAt(Instant.now());
        evt.setAction(action);
        return evt;
    }
}
