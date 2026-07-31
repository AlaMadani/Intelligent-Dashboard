package com.noveocare.dataprocessor.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.noveocare.dataprocessor.ai.FeatureEngineeringService;
import com.noveocare.dataprocessor.ai.churn.ChurnInferenceService;
import com.noveocare.dataprocessor.ai.churn.ChurnPrediction;
import com.noveocare.dataprocessor.ai.explanation.LlmEvidencePayloadService;
import com.noveocare.dataprocessor.ai.forecast.ForecastPrediction;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.ai.persona.PersonaRuntimeService;
import com.noveocare.dataprocessor.ai.sequence.EncodedSequenceEvent;
import com.noveocare.dataprocessor.ai.sequence.SequenceAnomalyScoringService;
import com.noveocare.dataprocessor.ai.sequence.SequenceFieldContribution;
import com.noveocare.dataprocessor.ai.sequence.SequenceInferenceResult;
import com.noveocare.dataprocessor.ai.sequence.SequenceModelKind;
import com.noveocare.dataprocessor.ai.sequence.SequenceOnnxInferenceService;
import com.noveocare.dataprocessor.ai.sequence.SequencePreprocessingService;
import com.noveocare.dataprocessor.ai.sequence.SequenceScoreResult;
import com.noveocare.dataprocessor.ai.sequence.SequenceWindow;
import com.noveocare.dataprocessor.ai.sequence.SequenceWindowService;
import com.noveocare.dataprocessor.ai.sequence.SequenceWindowState;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyFeatureService;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyFeatureVector;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyInferenceService;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyResult;
import com.noveocare.dataprocessor.config.AiChurnProperties;
import com.noveocare.dataprocessor.config.AiDiagnosticsProperties;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.config.AiLlmExplanationProperties;
import com.noveocare.dataprocessor.config.AiPersonaProperties;
import com.noveocare.dataprocessor.config.AiRiskFusionProperties;
import com.noveocare.dataprocessor.config.AiRiskScoringProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import com.noveocare.dataprocessor.config.InferenceConfigProperties;
import com.noveocare.dataprocessor.inference.InferenceConfig;
import com.noveocare.dataprocessor.inference.InferenceExecutorManager;
import com.noveocare.dataprocessor.config.AiLiveSessionProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import com.noveocare.dataprocessor.config.InferenceConfigProperties;
import com.noveocare.dataprocessor.config.KafkaConsumerProperties;
import com.noveocare.dataprocessor.config.KafkaTopicProperties;
import com.noveocare.dataprocessor.config.PerformanceProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.entity.SessionAnalysis;
import com.noveocare.dataprocessor.inference.AnomalyTypeAttributionServiceV36;
import com.noveocare.dataprocessor.inference.GeoJumpDetector;
import com.noveocare.dataprocessor.inference.LoadSheddingService;
import com.noveocare.dataprocessor.inference.ModelHealthService;
import com.noveocare.dataprocessor.inference.ModelInferenceService;
import com.noveocare.dataprocessor.inference.RiskFusionServiceV36;
import com.noveocare.dataprocessor.inference.RuleContribution;
import com.noveocare.dataprocessor.inference.RuleRiskResult;
import com.noveocare.dataprocessor.inference.RuleRiskScoringService;
import com.noveocare.dataprocessor.inference.VelocityDetector;
import com.noveocare.dataprocessor.kafka.AlertPublisher;
import com.noveocare.dataprocessor.kafka.AuditTrailConsumer;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import com.noveocare.dataprocessor.service.DashboardSnapshotService;
import com.noveocare.dataprocessor.service.ForecastRefreshService;
import com.noveocare.dataprocessor.service.EventIdempotencyService;
import com.noveocare.dataprocessor.service.SessionRunningSummaryService;
import com.noveocare.dataprocessor.service.SessionFinalizationOrchestrator;
import com.noveocare.dataprocessor.service.SessionFinalizationService;
import com.noveocare.dataprocessor.service.SessionRuleEvaluator;
import com.noveocare.dataprocessor.service.StatisticsService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Smoke tests for the full V3.6 data processor pipeline: model inference
 * through hybrid pipeline and Kafka consumer event processing with
 * session finalization and alert publishing.
 */
class DataprocessorV36SmokeTest {

    /* --- Fields --- */

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    /* --- Test methods: inference pipeline --- */

    @Test
    void modelInferenceProcessesAuditEventThroughHybridPipelineWithoutLlmCall() {
        AiSequenceProperties sequenceProperties = new AiSequenceProperties();
        sequenceProperties.setEnabled(true);
        sequenceProperties.setTransformerEnabled(true);
        sequenceProperties.setMinContextEvents(1);
        AiRiskScoringProperties riskProperties = new AiRiskScoringProperties();
        SequencePreprocessingService preprocessingService = mock(SequencePreprocessingService.class);
        SequenceWindowService windowService = mock(SequenceWindowService.class);
        SequenceOnnxInferenceService onnxInferenceService = mock(SequenceOnnxInferenceService.class);
        SequenceAnomalyScoringService scoringService = mock(SequenceAnomalyScoringService.class);
        LoadSheddingService loadSheddingService = mock(LoadSheddingService.class);
        TabularAnomalyFeatureService tabularFeatureService = mock(TabularAnomalyFeatureService.class);
        TabularAnomalyInferenceService tabularAnomalyInferenceService = mock(TabularAnomalyInferenceService.class);
        RuleRiskScoringService ruleRiskScoringService = mock(RuleRiskScoringService.class);
        ChurnInferenceService churnInferenceService = mock(ChurnInferenceService.class);
        ForecastRuntimeService forecastRuntimeService = mock(ForecastRuntimeService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        ModelHealthService modelHealthService = mock(ModelHealthService.class);
        ForecastRefreshService forecastRefreshService = mock(ForecastRefreshService.class);
        RedisCacheProperties redisProperties = redisProperties();

        AiTabularAnomalyProperties tabularProps = new AiTabularAnomalyProperties();
        AiChurnProperties churnProps = new AiChurnProperties();
        AiForecastProperties forecastProps = new AiForecastProperties();

        AiDiagnosticsProperties diagnosticsProps = new AiDiagnosticsProperties();

        com.noveocare.dataprocessor.service.NextEventPredictionService nextEventPredictionService =
                mock(com.noveocare.dataprocessor.service.NextEventPredictionService.class);

        ModelInferenceService service = new ModelInferenceService(
                sequenceProperties,
                riskProperties,
                diagnosticsProps,
                tabularProps,
                churnProps,
                forecastProps,
                preprocessingService,
                windowService,
                onnxInferenceService,
                scoringService,
                loadSheddingService,
                tabularFeatureService,
                tabularAnomalyInferenceService,
                ruleRiskScoringService,
                new RiskFusionServiceV36(new AiRiskFusionProperties()),
                new AnomalyTypeAttributionServiceV36(),
                new PersonaRuntimeService(new AiPersonaProperties()),
                churnInferenceService,
                forecastRuntimeService,
                new LlmEvidencePayloadService(new AiLlmExplanationProperties(), objectMapper),
                forecastRefreshService,
                redisCacheService,
                redisProperties,
                modelHealthService,
                new InferenceConfig(new InferenceConfigProperties(), new AiTabularAnomalyProperties(), sequenceProperties, new AiChurnProperties(), new AiForecastProperties()),
                new InferenceExecutorManager(),
                nextEventPredictionService);

        SessionSummary summary = summary();
        AuditTrailEvent previousEvent = auditEvent("evt-prev", "VIEW_HOME", "/api/home", Instant.parse("2026-05-25T02:14:00Z"));
        AuditTrailEvent currentEvent = auditEvent("evt-current", "DOWNLOAD_DOCUMENT", "/api/documents/{id}/download", Instant.parse("2026-05-25T02:15:00Z"));
        EncodedSequenceEvent previousEncoded = encoded("evt-prev");
        EncodedSequenceEvent currentEncoded = encoded("evt-current");
        SequenceWindowState previousState = SequenceWindowState.builder().events(List.of(previousEncoded)).build();
        SequenceWindow previousWindow = SequenceWindow.builder().realEventCount(1).build();
        SequenceInferenceResult inferenceResult = SequenceInferenceResult.builder()
                .modelKind(SequenceModelKind.TRANSFORMER)
                .modelArtifact("transformer_sequence_engine.onnx")
                .latencyMillis(8L)
                .build();
        SequenceScoreResult transformerScore = SequenceScoreResult.builder()
                .available(true)
                .modelKind("transformer")
                .modelArtifact("transformer_sequence_engine.onnx")
                .sequenceAnomalyScore(3.2)
                .categoricalScore(1.4)
                .continuousScore(0.9)
                .contextScore(0.4)
                .aiRiskScore(79.0)
                .latencyMillis(8L)
                .topContributingFields(List.of(SequenceFieldContribution.builder()
                        .field("api_template")
                        .contribution(0.42)
                        .rawValue("/api/documents/{id}/download")
                        .build()))
                .perFieldContributions(List.of())
                .warnings(List.of())
                .build();
        TabularAnomalyFeatureVector vector = TabularAnomalyFeatureVector.builder()
                .contractArtifact("tabular_anomaly_feature_contract.json")
                .featureOrder(List.of("window_length_ratio"))
                .rawValues(new double[]{0.1})
                .scaledValues(new double[]{0.1})
                .rawFeatureMap(Map.of("window_length_ratio", 0.1))
                .warnings(List.of())
                .build();
        TabularAnomalyResult tabular = TabularAnomalyResult.builder()
                .xgboostAnomalyScore(0.91)
                .xgboostAnomalyScore100(91.0)
                .xgboostArtifact("anomaly_xgboost.json")
                .lightgbmAlertScore(0.88)
                .lightgbmAlertScore100(88.0)
                .lightgbmArtifact("anomaly_lightgbm.txt")
                .oneClassSvmNoveltyScoreRaw(1.7)
                .oneClassSvmNoveltyScore100(84.0)
                .oneClassSvmArtifact("anomaly_oneclasssvm.json")
                .availableModels(List.of("xgboost", "lightgbm", "oneclasssvm"))
                .unavailableModels(List.of("catboost"))
                .modelWarnings(List.of("catboost_anomaly_unavailable"))
                .latencyByModel(Map.of("xgboost", 1L, "lightgbm", 1L, "oneclasssvm", 1L))
                .tabularFeatureWarnings(List.of())
                .build();
        RuleRiskResult rules = rules();
        ForecastPrediction forecast = forecast();

        when(windowService.load(summary.getSessionId())).thenReturn(previousState);
        when(windowService.latestTimestamp(previousState)).thenReturn(previousEvent.getCreatedAt());
        when(preprocessingService.encode(currentEvent, previousEvent.getCreatedAt())).thenReturn(currentEncoded);
        when(ruleRiskScoringService.evaluate(summary, List.of(previousEvent, currentEvent), List.of("OFF_HOURS_ACCESS"))).thenReturn(rules);
        when(loadSheddingService.selectModel(0L)).thenReturn(SequenceModelKind.TRANSFORMER);
        when(windowService.toWindow(previousState)).thenReturn(previousWindow);
        when(onnxInferenceService.infer(SequenceModelKind.TRANSFORMER, previousWindow)).thenReturn(inferenceResult);
        when(scoringService.score(inferenceResult, currentEncoded)).thenReturn(transformerScore);
        when(tabularFeatureService.build(previousState, currentEncoded)).thenReturn(vector);
        when(tabularAnomalyInferenceService.score(vector)).thenReturn(tabular);
        when(churnInferenceService.predict(eq(summary), eq(List.of(previousEvent, currentEvent)), eq(true)))
                .thenReturn(ChurnPrediction.builder()
                        .available(true)
                        .probability(0.72)
                        .riskLevel("HIGH")
                        .modelName("profile_only_ExtraTrees")
                        .modelArtifact("churn_profile_only_ExtraTrees.json")
                        .warnings(List.of())
                        .build());
        when(forecastRuntimeService.forecast(any(LocalDate.class))).thenReturn(forecast);
        when(forecastRuntimeService.ridgeLoaded()).thenReturn(true);
        when(forecastRuntimeService.xgboostLoaded()).thenReturn(true);

        SessionInsight insight = service.infer(summary, List.of(previousEvent, currentEvent), List.of("OFF_HOURS_ACCESS"), 0L);

        assertThat(insight.getFinalRiskScore()).isBetween(0.0, 100.0);
        assertThat(insight.getRiskLevel()).isNotBlank();
        assertThat(insight.getModelScores()).containsEntry("xgboostAnomalyScore", 0.91);
        assertThat(insight.getModelScores()).containsEntry("xgboostAnomalyScore100", 91.0);
        assertThat(insight.getModelScores()).containsEntry("transformerSurpriseScoreRaw", 3.2);
        assertThat(insight.getModelScores()).containsEntry("transformerRiskScore100", 79.0);
        assertThat(insight.getModelContributions()).containsKeys("xgboost", "lightgbm", "transformer", "rules", "aggregationBoost");
        assertThat(insight.getTriggeredRules()).contains("LARGE_DOWNLOAD", "DATA_EXTRACTION_PATTERN");
        assertThat(insight.getAnomalyType()).isEqualTo("data_exfiltration");
        assertThat(insight.getAnomalyTypeConfidence()).isEqualTo(0.82);
        assertThat(insight.getChurnProbability()).isEqualTo(0.72);
        assertThat(insight.getForecastContext()).containsEntry("schemaVersion", "v3.6.1");
        assertThat(insight.getPersonaCluster()).isEqualTo(-1);
        assertThat(insight.getPersonaLabel()).isEqualTo("persona_disabled");
        assertThat(insight.getPersonaSource()).isEqualTo("disabled_v3_6_refactor");
        assertThat(insight.getPersonaWarnings()).contains("persona_skipped_for_now");
        assertThat(insight.getLlmExplanationEvidencePayload()).containsEntry("schemaVersion", "v3.6.1");
        assertThat(insight.getInvestigationPayload()).containsEntry("schemaVersion", "v3.6.1");
        assertThat(insight.getInvestigationPayload()).containsEntry("llmEvidencePayloadAvailable", true);

        InOrder scoringOrder = inOrder(onnxInferenceService, windowService);
        scoringOrder.verify(onnxInferenceService).infer(SequenceModelKind.TRANSFORMER, previousWindow);
        scoringOrder.verify(windowService).appendAndSave(summary.getSessionId(), currentEncoded);
        verify(tabularFeatureService).build(previousState, currentEncoded);
        verify(redisCacheService).setJson(eq(CacheKeys.alertLlmEvidenceKey("evt-current")), any(), any());
        verify(redisCacheService).setJson(eq(CacheKeys.alertInvestigationKey("evt-current")), any(), any());
    }

    /* --- Test methods: consumer pipeline --- */

    @Test
    void workerConsumesEventPersistsV36FieldsCachesRedisPayloadsAndPublishesLightweightKafkaAlert() throws Exception {
        RedisSessionBufferService sessionBufferService = mock(RedisSessionBufferService.class);
        FeatureEngineeringService featureEngineeringService = mock(FeatureEngineeringService.class);
        ModelInferenceService modelInferenceService = mock(ModelInferenceService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        StatisticsService statisticsService = mock(StatisticsService.class);
        SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
        AlertPublisher alertPublisher = mock(AlertPublisher.class);
        VelocityDetector velocityDetector = mock(VelocityDetector.class);
        GeoJumpDetector geoJumpDetector = mock(GeoJumpDetector.class);
        DashboardSnapshotService dashboardSnapshotService = mock(DashboardSnapshotService.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        RuleProperties ruleProperties = new RuleProperties();
        ruleProperties.setSessionEndActions(List.of("LOGOUT"));
        FeatureEngineeringProperties featureProperties = new FeatureEngineeringProperties();
        featureProperties.setSessionAlertRiskThreshold(60.0);
        KafkaTopicProperties topicProperties = new KafkaTopicProperties();
        KafkaConsumerProperties consumerProperties = new KafkaConsumerProperties();
        SessionFinalizationService finalizationService = mock(SessionFinalizationService.class);
        SessionFinalizationOrchestrator finalizationOrchestrator = mock(SessionFinalizationOrchestrator.class);
        SessionRuleEvaluator ruleEvaluator = mock(SessionRuleEvaluator.class);
        EventIdempotencyService idempotencyService = mock(EventIdempotencyService.class);
        when(idempotencyService.tryMarkProcessing(any())).thenReturn(true);

        AuditTrailEvent event = auditEvent("evt-current", "LOGOUT", "/api/documents/{id}/download", Instant.parse("2026-05-25T02:15:00Z"));
        SessionSummary mockSummary = summary().toBuilder().lastAction("LOGOUT").build();
        SessionInsight insight = persistedInsight();

        SessionRunningSummaryService runningSummaryService = mock(SessionRunningSummaryService.class);
        when(runningSummaryService.loadOrCreate(anyString(), anyString()))
                .thenAnswer(inv -> new com.noveocare.dataprocessor.dto.SessionRunningSummary());
        when(runningSummaryService.updateWithEvent(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(runningSummaryService.toLiveSessionSummary(any(), anyList()))
                .thenReturn(mockSummary);

        AiLiveSessionProperties liveSessionProps = new AiLiveSessionProperties();
        liveSessionProps.setRecentEventsLimit(50);
        AuditTrailConsumer consumer = new AuditTrailConsumer(
                objectMapper,
                sessionBufferService,
                featureEngineeringService,
                modelInferenceService,
                redisCacheService,
                redisProperties(),
                featureProperties,
                statisticsService,
                dashboardSnapshotService,
                kafkaTemplate,
                topicProperties,
                consumerProperties,
                finalizationService,
                finalizationOrchestrator,
                ruleEvaluator,
                idempotencyService,
                new PerformanceProperties(),
                runningSummaryService,
                liveSessionProps);

        when(sessionBufferService.getRecentSessionEvents(event.getInsuredId(), event.getSessionId(), 50)).thenReturn(List.of(event));
        when(sessionBufferService.getSessionEvents(event.getInsuredId(), event.getSessionId())).thenReturn(List.of(event));
        when(featureEngineeringService.enrichSessionEvents(List.of(event))).thenReturn(List.of(event));
        when(featureEngineeringService.buildSessionSummary(List.of(event))).thenReturn(mockSummary);
        when(modelInferenceService.infer(eq(mockSummary), eq(List.of(event)), anyList(), anyLong())).thenReturn(insight);
        when(finalizationService.isExplicitSessionEnd(any(AuditTrailEvent.class))).thenReturn(true);
        when(finalizationService.resolveEndReason(any(AuditTrailEvent.class))).thenReturn("explicit_logout");
        @SuppressWarnings("unchecked")
        Consumer<String, String> kafkaConsumer = mock(Consumer.class);
        when(kafkaConsumer.endOffsets(anySet())).thenReturn(Map.of(new TopicPartition("audit-trail", 0), 1L));

        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.consume(new ConsumerRecord<>("audit-trail", 0, 0L, event.getInsuredId(), objectMapper.writeValueAsString(event)), ack, kafkaConsumer);

        verify(sessionBufferService).appendEvent(any(AuditTrailEvent.class));
        verify(finalizationService).handleIncomingEvent(any(AuditTrailEvent.class));
        verify(finalizationOrchestrator).completeFinalization(
                eq(mockSummary), eq(insight), eq(List.of(event)), anyList(), eq("explicit_logout"), eq(true));
        verify(dashboardSnapshotService).cacheSessionInsight(eq(mockSummary), eq(insight));
    }

    /* --- Helper methods --- */

    private RedisCacheProperties redisProperties() {
        RedisCacheProperties properties = new RedisCacheProperties();
        properties.setSessionBuffer(Duration.ofHours(2));
        properties.setSessionInsight(Duration.ofHours(24));
        properties.setLiveStats(Duration.ofHours(1));
        properties.setDashboard(Duration.ofMinutes(10));
        properties.setForecast(Duration.ofMinutes(30));
        properties.setActiveAnomaly(Duration.ofHours(24));
        return properties;
    }

    private SessionSummary summary() {
        return SessionSummary.builder()
                .insuredId("insured-42")
                .sessionId("session-99")
                .countryCode("MA")
                .city("Casablanca")
                .sessionStart(Instant.parse("2026-05-25T02:10:00Z"))
                .sessionEnd(Instant.parse("2026-05-25T02:15:00Z"))
                .startHour(2)
                .endHour(2)
                .firstAction("LOGIN")
                .lastAction("DOWNLOAD_DOCUMENT")
                .firstRoute("/home")
                .lastRoute("/documents")
                .totalEvents(12)
                .totalDurationSeconds(300L)
                .avgInterActionSeconds(12.0)
                .uniqueActions(5)
                .uniqueRoutes(4)
                .uniqueIpsUsed(2)
                .uniqueDevicesUsed(2)
                .totalKOs(1)
                .totalOKs(11)
                .ipChanged(1)
                .deviceChanged(1)
                .totalDownloadActions(10)
                .maxDownloadsIn2Minutes(10)
                .pingPongCount(3)
                .actionSequence(List.of("LOGIN", "VIEW_HOME", "DOWNLOAD_DOCUMENT"))
                .routeSequence(List.of("/login", "/home", "/documents"))
                .actionSequenceSignature("LOGIN>VIEW_HOME>DOWNLOAD_DOCUMENT")
                .routeSequenceSignature("/login>/home>/documents")
                .actionCounts(Map.of("DOWNLOAD_DOCUMENT", 10L, "LOGIN", 1L))
                .build();
    }

    private AuditTrailEvent auditEvent(String eventId, String action, String apiTemplate, Instant timestamp) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setId(eventId);
        event.setInsuredId("insured-42");
        event.setSessionId("session-99");
        event.setAction(action);
        event.setActionValue(action);
        event.setActionType("document");
        event.setStatus("OK");
        event.setApiTemplate(apiTemplate);
        event.setApiFamily("documents");
        event.setController("DocumentController");
        event.setPage("/documents");
        event.setCountryCode("MA");
        event.setIpCountry("MA");
        event.setDevice("desktop");
        event.setBrowser("Chrome");
        event.setOs("Windows");
        event.setHttpMethod("GET");
        event.setCreatedAt(timestamp);
        event.setIsBusinessHours(0);
        event.setDownloadsLast2Minutes(10);
        event.setResponseDataSizeBytes(7_000_000L);
        event.setRequestDataSizeBytes(512L);
        return event;
    }

    private EncodedSequenceEvent encoded(String eventId) {
        return EncodedSequenceEvent.builder()
                .insuredId("insured-42")
                .sessionId("session-99")
                .eventId(eventId)
                .timestamp(Instant.parse("2026-05-25T02:15:00Z"))
                .categoricalIds(new long[15])
                .continuousValues(new float[9])
                .rawContinuousValues(new double[9])
                .rawCategoricalValues(Map.of("api_template", "/api/documents/{id}/download"))
                .warnings(List.of())
                .schemaValid(true)
                .build();
    }

    private RuleRiskResult rules() {
        List<String> codes = List.of(
                "OFF_HOURS_ACCESS",
                "SENSITIVE_API_OFF_HOURS",
                "LARGE_DOWNLOAD",
                "DATA_EXTRACTION_PATTERN");
        return RuleRiskResult.builder()
                .ruleRiskScore(85.0)
                .triggeredRules(codes)
                .ruleContributions(List.of(RuleContribution.builder()
                        .ruleCode("LARGE_DOWNLOAD")
                        .severity("HIGH")
                        .scoreContribution(35.0)
                        .humanMessage("Large document download outside normal hours.")
                        .evidenceJson(Map.of("downloadsLast2Minutes", 10))
                        .build()))
                .ruleEvidence(Map.of("downloadsLast2Minutes", 10, "isBusinessHours", false))
                .businessContextScore(0.0)
                .build();
    }

    private ForecastPrediction forecast() {
        return ForecastPrediction.builder()
                .referenceDate(LocalDate.now(ZoneOffset.UTC))
                .forecastDate(LocalDate.now(ZoneOffset.UTC).plusDays(1))
                .anomalyRateForecast(0.018)
                .totalEventsForecast(48_200.0)
                .expectedAlertVolume(867.6)
                .anomalyRateModelName("Ridge")
                .anomalyRateModelArtifact("macro_forecaster_anomaly_rate_Ridge.json")
                .totalEventsModelName("XGBoost")
                .totalEventsModelArtifact("macro_forecaster_total_events_XGBoost.json")
                .warnings(List.of())
                .totalEventsFeatures(Map.of())
                .anomalyRateFeatures(Map.of())
                .build();
    }

    private SessionInsight persistedInsight() {
        Map<String, Object> modelScores = Map.of(
                "xgboostAnomalyScore", 0.91,
                "xgboostAnomalyScore100", 91.0,
                "lightgbmAlertScore", 0.88,
                "lightgbmAlertScore100", 88.0,
                "transformerRiskScore100", 79.0,
                "tcnRiskScore100", 74.0,
                "ruleRiskScore", 85.0);
        Map<String, Object> modelContributions = Map.of(
                "xgboost", 27.3,
                "lightgbm", 22.0,
                "transformer", 15.8,
                "tcn", 7.4,
                "rules", 12.8,
                "businessContext", 0.0,
                "aggregationBoost", 0.0);
        Map<String, Object> evidence = Map.of(
                "schemaVersion", "v3.6.1",
                "eventId", "evt-current",
                "risk", Map.of("finalRiskScore", 87.0, "riskLevel", "CRITICAL"),
                "modelScores", modelScores,
                "llmExplanationInDataprocessor", false);
        return SessionInsight.builder()
                .insuredId("insured-42")
                .sessionId("session-99")
                .computedAt(Instant.parse("2026-05-25T02:15:01Z"))
                .binaryAnomaly(true)
                .anomaly(true)
                .anomalyScore(87.0)
                .anomalyProbability(0.87)
                .binaryDetectorArtifact("risk_fusion_v3_6_1")
                .anomalyType("data_exfiltration")
                .anomalyTypeSource("hybrid_rules_models")
                .anomalyTypeConfidence(0.82)
                .churnProbability(0.72)
                .churnRiskLevel("HIGH")
                .churnModelName("profile_only_ExtraTrees")
                .churnModelArtifact("churn_profile_only_ExtraTrees.json")
                .churnFeatureWarnings(List.of())
                .personaCluster(-1)
                .personaLabel("persona_disabled")
                .personaSource("disabled_v3_6_refactor")
                .personaConfidence(0.0)
                .personaWarnings(List.of("persona_skipped_for_now"))
                .ensembleRiskScore(87.0)
                .riskLevel("CRITICAL")
                .contextTags(List.of("OFF_HOURS_ACCESS", "LARGE_DOWNLOAD"))
                .triggeredRules(List.of("OFF_HOURS_ACCESS", "LARGE_DOWNLOAD"))
                .warnings(List.of())
                .topContributingFeatures(List.of())
                .sequenceModelPrimary("transformer")
                .sequenceModelFast("tcn")
                .selectedSequenceModel("transformer")
                .sequenceModelArtifact("transformer_sequence_engine.onnx")
                .transformerScore(3.2)
                .transformerRiskScore100(79.0)
                .tcnScore(2.7)
                .tcnRiskScore100(74.0)
                .sequenceAnomalyScore(3.2)
                .sequenceCategoricalScore(1.4)
                .sequenceContinuousScore(0.9)
                .sequenceContextScore(0.4)
                .sequenceContextAvailable(true)
                .aiRiskScore(91.0)
                .ruleRiskScore(85.0)
                .ruleContributions(List.of())
                .ruleEvidence(Map.of())
                .finalRiskScore(87.0)
                .sequenceTopContributions(List.of())
                .anomalyTypeEvidence(Map.of("xgboostScore100", 91.0))
                .forecastTotalEvents(48_200.0)
                .forecastAnomalyRate(0.018)
                .forecastExpectedAlertVolume(867.6)
                .forecastContext(Map.of("schemaVersion", "v3.6.1", "predictedTotalEvents", 48_200.0))
                .xgboostAnomalyScore(0.91)
                .xgboostAnomalyScore100(91.0)
                .xgboostArtifact("anomaly_xgboost.json")
                .lightgbmAlertScore(0.88)
                .lightgbmAlertScore100(88.0)
                .lightgbmArtifact("anomaly_lightgbm.txt")
                .availableTabularModels(List.of("xgboost", "lightgbm"))
                .unavailableTabularModels(List.of("catboost"))
                .tabularWarnings(List.of("catboost_anomaly_unavailable"))
                .businessContextScore(0.0)
                .aggregationBoost(0.0)
                .riskFusionWeights(Map.of("xgboost", 0.30, "lightgbm", 0.25, "transformer", 0.20, "tcn", 0.10, "rules", 0.15))
                .unavailableModelWeights(Map.of())
                .modelScores(modelScores)
                .modelContributions(modelContributions)
                .fallbackMode("FULL_HYBRID")
                .llmExplanationEvidencePayload(evidence)
                .investigationPayload(Map.of(
                        "schemaVersion", "v3.6.1",
                        "eventId", "evt-current",
                        "finalRiskScore", 87.0,
                        "llmEvidencePayloadAvailable", true))
                .modelArtifacts(Map.of(
                        "ranking", "anomaly_xgboost.json",
                        "alerting", "anomaly_lightgbm.txt",
                        "sequence", "transformer_sequence_engine.onnx",
                        "fallback", "tcn_sequence_engine.onnx",
                        "churn", "churn_profile_only_ExtraTrees.json"))
                .build();
    }
}
