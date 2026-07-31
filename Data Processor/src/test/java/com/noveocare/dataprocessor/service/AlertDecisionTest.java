package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for alert decision logic in SessionFinalizationOrchestrator:
 * threshold-based alert eligibility, duplicate prevention, payload
 * structure with risk levels, event metadata, sequence and tabular evidence.
 */
@ExtendWith(MockitoExtension.class)
class AlertDecisionTest {

    /* --- Mock fields --- */

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

    /* --- Fields --- */

    private FeatureEngineeringProperties featureProperties;
    private RedisCacheProperties redisCacheProperties;
    private SessionFinalizationOrchestrator orchestrator;

    private final double SESSION_ALERT_RISK_THRESHOLD = 60.0;

    /* --- Setup --- */

    @BeforeEach
    void setUp() {
        featureProperties = new FeatureEngineeringProperties();
        featureProperties.setSessionAlertRiskThreshold(SESSION_ALERT_RISK_THRESHOLD);
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

    /* --- Test methods: alert thresholds --- */

    @Test
    void finalRiskBelow35NoRulesNoAlert() {
        SessionInsight insight = insightWith(30.0, false, List.of());
        assertThat(orchestrator.shouldAlert(insight)).isFalse();
    }

    @Test
    void finalRisk35WithNoRulesIsAlertEligible() {
        SessionInsight insight = insightWith(35.0, true, List.of());
        assertThat(orchestrator.shouldAlert(insight)).isTrue();
    }

    @Test
    void finalRisk35WithRulesIsAlertEligible() {
        SessionInsight insight = insightWith(35.89, true, List.of("API_SCRAPING_PATTERN"));
        assertThat(orchestrator.shouldAlert(insight)).isTrue();
    }

    @Test
    void finalRisk60IsAlertEligibleEvenIfNotAnomaly() {
        SessionInsight insight = insightWith(60.0, false, List.of());
        assertThat(orchestrator.shouldAlert(insight)).isTrue();
    }

    @Test
    void finalRisk59IsNotAlertEligibleIfNotAnomaly() {
        SessionInsight insight = insightWith(59.0, false, List.of());
        assertThat(orchestrator.shouldAlert(insight)).isFalse();
    }

    @Test
    void finalRisk59IsAlertEligibleIfAnomaly() {
        SessionInsight insight = insightWith(59.0, true, List.of());
        assertThat(orchestrator.shouldAlert(insight)).isTrue();
    }

    /* --- Test methods: alert trigger --- */

    @Test
    void alertReasonIsAnomalyThresholdWhenAnomalyTrueNoRules() {
        SessionInsight insight = insightWith(35.0, true, List.of());
        when(redisCacheService.hasKey(anyString())).thenReturn(false);
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        List<AuditTrailEvent> events = List.of(createEvent("evt-1", "login"));

        orchestrator.completeFinalization(summary(), insight, events, List.of(), "test", false);

        verify(alertPublisher, atLeastOnce()).publish(any(), anyString());
    }

    /* --- Test methods: deduplication --- */

    @Test
    void duplicateAlertNotPublished() {
        SessionInsight insight = insightWith(80.0, true, List.of("RAPID_FIRE_EVENTS"));
        when(redisCacheService.hasKey(anyString())).thenReturn(true);

        List<AuditTrailEvent> events = List.of(createEvent("evt-1", "login"));

        orchestrator.completeFinalization(summary(), insight, events, List.of(), "test", false);

        verify(alertPublisher, never()).publish(any(), anyString());
    }

    /* --- Test methods: payload structure --- */

    @Test
    void alertPayloadHasRiskLevelRiskTierAndRiskScale() {
        SessionInsight insight = insightWith(45.0, true, List.of("API_SCRAPING_PATTERN"));
        when(redisCacheService.hasKey(anyString())).thenReturn(false);
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        List<AuditTrailEvent> events = List.of(createEvent("evt-1", "login"));

        orchestrator.completeFinalization(summary(), insight, events, List.of(), "test", false);

        ArgumentCaptor<AnomalyAlert> alertCaptor = ArgumentCaptor.forClass(AnomalyAlert.class);
        verify(alertPublisher, atLeastOnce()).publish(alertCaptor.capture(), anyString());
        AnomalyAlert alert = alertCaptor.getValue();

        assertThat(alert.getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(alert.getRiskTier()).isEqualTo("MEDIUM");
        assertThat(alert.getRiskScale()).isEqualTo("ZERO_TO_ONE_HUNDRED");
    }

    /* --- Test methods: event metadata --- */

    @Test
    void alertPayloadContainsEventMetadata() {
        SessionInsight insight = insightWith(45.0, true, List.of());
        when(redisCacheService.hasKey(anyString())).thenReturn(false);
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        AuditTrailEvent evt = createEvent("evt-1", "search");
        evt.setApiTemplate("/api/v1/search");
        evt.setApiFamily("search");
        evt.setController("SearchController");
        evt.setIpCountry("US");
        evt.setDevice("mobile");
        evt.setBrowser("Chrome");
        evt.setOs("Android");
        evt.setHttpMethod("GET");
        evt.setStatus("200");
        List<AuditTrailEvent> events = List.of(evt);

        orchestrator.completeFinalization(summary(), insight, events, List.of(), "test", false);

        ArgumentCaptor<AnomalyAlert> alertCaptor = ArgumentCaptor.forClass(AnomalyAlert.class);
        verify(alertPublisher, atLeastOnce()).publish(alertCaptor.capture(), anyString());
        AnomalyAlert alert = alertCaptor.getValue();

        assertThat(alert.getEventAction()).isEqualTo("search");
        assertThat(alert.getApiTemplate()).isEqualTo("/api/v1/search");
        assertThat(alert.getEventMetadata()).isNotNull();
        assertThat(alert.getEventMetadata().get("action")).isEqualTo("search");
        assertThat(alert.getEventMetadata().get("device")).isEqualTo("mobile");
        assertThat(alert.getEventMetadata().get("browser")).isEqualTo("Chrome");
    }

    /* --- Test methods: sequence evidence --- */

    @Test
    void alertPayloadContainsSequenceEvidence() {
        SessionInsight insight = SessionInsight.builder()
                .insuredId("insured-1")
                .sessionId("sess-1")
                .computedAt(Instant.now())
                .anomaly(true)
                .anomalyScore(45.0)
                .anomalyProbability(0.45)
                .finalRiskScore(45.0)
                .riskLevel("MEDIUM")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .triggeredRules(List.of())
                .selectedSequenceModel("TRANSFORMER")
                .sequenceContextAvailable(true)
                .sequenceAnomalyScore(27.53)
                .sequenceCategoricalScore(15.0)
                .sequenceContinuousScore(8.0)
                .sequenceContextScore(4.53)
                .transformerScore(27.53)
                .transformerRiskScore100(99.59)
                .sequenceLatencyMs(5L)
                .build();

        when(redisCacheService.hasKey(anyString())).thenReturn(false);
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        List<AuditTrailEvent> events = List.of(createEvent("evt-1", "login"));

        orchestrator.completeFinalization(summary(), insight, events, List.of(), "test", false);

        ArgumentCaptor<AnomalyAlert> alertCaptor = ArgumentCaptor.forClass(AnomalyAlert.class);
        verify(alertPublisher, atLeastOnce()).publish(alertCaptor.capture(), anyString());
        AnomalyAlert alert = alertCaptor.getValue();

        assertThat(alert.getSequenceEvidence()).isNotNull();
        assertThat(alert.getSequenceEvidence().get("selectedModel")).isEqualTo("TRANSFORMER");
        assertThat(alert.getSequenceEvidence().get("contextAvailable")).isEqualTo(true);
        assertThat(alert.getSequenceEvidence().get("anomalyScore")).isEqualTo(27.53);
        assertThat(alert.getSequenceEvidence().get("available")).isEqualTo(true);
    }

    /* --- Test methods: tabular evidence --- */

    @Test
    void alertPayloadContainsTabularEvidence() {
        SessionInsight insight = SessionInsight.builder()
                .insuredId("insured-1")
                .sessionId("sess-1")
                .computedAt(Instant.now())
                .anomaly(true)
                .anomalyScore(45.0)
                .anomalyProbability(0.45)
                .finalRiskScore(45.0)
                .riskLevel("MEDIUM")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .triggeredRules(List.of())
                .availableTabularModels(List.of("xgboost", "lightgbm"))
                .unavailableTabularModels(List.of("catboost", "oneclasssvm"))
                .tabularWarnings(List.of("catboost_skipped"))
                .build();

        when(redisCacheService.hasKey(anyString())).thenReturn(false);
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());

        List<AuditTrailEvent> events = List.of(createEvent("evt-1", "login"));

        orchestrator.completeFinalization(summary(), insight, events, List.of(), "test", false);

        ArgumentCaptor<AnomalyAlert> alertCaptor = ArgumentCaptor.forClass(AnomalyAlert.class);
        verify(alertPublisher, atLeastOnce()).publish(alertCaptor.capture(), anyString());
        AnomalyAlert alert = alertCaptor.getValue();

        assertThat(alert.getTabularEvidence()).isNotNull();
        assertThat(alert.getTabularEvidence().get("availableModels")).isEqualTo(List.of("xgboost", "lightgbm"));
        assertThat(alert.getTabularEvidence().get("unavailableModels")).isEqualTo(List.of("catboost", "oneclasssvm"));
        assertThat(alert.getTabularEvidence().get("available")).isEqualTo(true);
    }

    /* --- Helper methods --- */

    private SessionInsight insightWith(double finalRiskScore, boolean anomaly, List<String> rules) {
        return SessionInsight.builder()
                .insuredId("insured-1")
                .sessionId("sess-1")
                .computedAt(Instant.now())
                .anomaly(anomaly)
                .anomalyScore(finalRiskScore)
                .anomalyProbability(finalRiskScore / 100.0)
                .finalRiskScore(finalRiskScore)
                .ensembleRiskScore(finalRiskScore)
                .riskLevel(finalRiskScore >= 80.0 ? "CRITICAL" : finalRiskScore >= 60.0 ? "HIGH" : finalRiskScore >= 35.0 ? "MEDIUM" : "LOW")
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .triggeredRules(rules)
                .build();
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
