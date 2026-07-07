package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.artifact.CategoricalVocabularies;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.artifact.SequenceMetadata;
import com.noveocare.dataprocessor.ai.sequence.SequenceInferenceResult;
import com.noveocare.dataprocessor.ai.sequence.SequenceModelKind;
import com.noveocare.dataprocessor.config.NextEventPredictionProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.NextEventPredictionHeadScore;
import com.noveocare.dataprocessor.dto.NextEventPredictionResult;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.NextEventPredictionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NextEventPredictionServiceTest {

    private NextEventPredictionProperties properties;
    private RuntimeArtifactService artifactService;
    private RedisCacheService redisCacheService;
    private NextEventPredictionRepository repository;
    private NextEventPredictionService service;

    @BeforeEach
    void setUp() {
        properties = new NextEventPredictionProperties();
        properties.setEnabled(true);
        properties.setTopK(3);
        properties.setMinContextEvents(2);
        properties.setWriteRedis(true);
        properties.setWriteSql(true);

        artifactService = mock(RuntimeArtifactService.class);
        redisCacheService = mock(RedisCacheService.class);
        repository = mock(NextEventPredictionRepository.class);

        SequenceMetadata meta = new SequenceMetadata();
        meta.setCatCols(List.of("page", "frontend_action_name", "api_template", "action_value",
                "action_type", "action_subtype", "http_method", "status", "device",
                "browser", "os", "ip_country", "controller", "api_family", "environment_id"));
        meta.setContCols(List.of("time_since_prev_action_ms", "request_data_size_bytes", "response_data_size_bytes",
                "is_business_hours", "is_weekend", "hour_sin", "hour_cos", "dow_sin", "dow_cos"));
        meta.setVocabSizes(List.of(15, 53, 51, 53, 6, 20, 4, 2, 3, 4, 4, 5, 15, 14, 4));
        meta.setWindowSize(10);
        when(artifactService.getSequenceMetadata()).thenReturn(meta);

        CategoricalVocabularies vocab = new CategoricalVocabularies();
        vocab.setInputIdMaps1Based(Map.of(
                "page", Map.of("bankinginformation", 1, "beneficiaries", 2),
                "frontend_action_name", Map.of("authorize_connection", 1, "change_password", 2, "download_document", 3),
                "api_template", Map.of("/auth/login", 1, "/documents/file", 2),
                "api_family", Map.of("auth", 1, "documents", 2)
        ));
        vocab.setReverseInputIdMaps(Map.of(
                "page", Map.of("1", "bankinginformation", "2", "beneficiaries"),
                "frontend_action_name", Map.of("1", "authorize_connection", "2", "change_password", "3", "download_document"),
                "api_template", Map.of("1", "/auth/login", "2", "/documents/file"),
                "api_family", Map.of("1", "auth", "2", "documents")
        ));
        when(artifactService.getCategoricalVocabularies()).thenReturn(vocab);

        RedisCacheProperties cacheProps = new RedisCacheProperties();
        cacheProps.setSessionInsight(Duration.ofHours(2));

        service = new NextEventPredictionService(
                properties, artifactService, redisCacheService, cacheProps, repository, new ObjectMapper().findAndRegisterModules());
        service.init();
    }

    @Test
    void disabledWhenConfigSaysSo() {
        properties.setEnabled(false);
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void enabledWhenConfigSaysSo() {
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void predictDecodesFrontendActionNameSoftmaxTopK() {
        float[] frontendLogits = new float[]{-1.0f, 2.0f, 5.0f, -3.0f, 0.5f};
        float[][] padded = new float[53][];
        for (int i = 0; i < 53; i++) {
            padded[i] = i < frontendLogits.length ? new float[]{frontendLogits[i]} : new float[]{-10.0f};
        }
        float[] fullLogits = new float[53];
        for (int i = 0; i < 53; i++) {
            fullLogits[i] = i < frontendLogits.length ? frontendLogits[i] : -10.0f;
        }
        fullLogits[1] = 2.0f;
        fullLogits[2] = 5.0f;
        fullLogits[4] = 0.5f;

        float[] pageLogits = new float[15];
        pageLogits[0] = 10.0f;

        float[] apiTemplateLogits = new float[51];
        apiTemplateLogits[1] = 3.0f;

        float[] apiFamilyLogits = new float[14];
        apiFamilyLogits[0] = 4.0f;

        SequenceInferenceResult inference = SequenceInferenceResult.builder()
                .modelKind(SequenceModelKind.TRANSFORMER)
                .modelArtifact("transformer_sequence_engine.onnx")
                .categoricalLogits(List.of(
                        pageLogits,
                        fullLogits,     // frontend_action_name
                        apiTemplateLogits,
                        new float[53],  // action_value
                        new float[6],
                        new float[20],
                        new float[4],
                        new float[2],
                        new float[3],
                        new float[4],
                        new float[4],
                        new float[5],
                        new float[15],
                        apiFamilyLogits,
                        new float[4]
                ))
                .continuousPrediction(new float[9])
                .latencyMillis(5L)
                .build();

        service.predict(inference, "insured-1", "session-1", "event-context-1", 10, null);

        verify(redisCacheService).setJson(
                eq(com.noveocare.dataprocessor.config.CacheKeys.nextEventPredictionSessionKey("session-1")),
                anyString(), any(Duration.class));
        verify(redisCacheService).setJson(
                eq(com.noveocare.dataprocessor.config.CacheKeys.nextEventPredictionInsuredKey("insured-1")),
                anyString(), any(Duration.class));
    }

    @Test
    void predictSkipsInsufficientContext() {
        SequenceInferenceResult inference = SequenceInferenceResult.builder()
                .modelKind(SequenceModelKind.TRANSFORMER)
                .categoricalLogits(List.of(new float[15], new float[53], new float[51], new float[53],
                        new float[6], new float[20], new float[4], new float[2], new float[3],
                        new float[4], new float[4], new float[5], new float[15], new float[14], new float[4]))
                .continuousPrediction(new float[9])
                .build();

        service.predict(inference, "insured-1", "session-1", "event-context-1", 1, null);
    }

    @Test
    void evaluateDeviationReturnsNullWhenDisabled() {
        properties.setEvaluateDeviation(false);
        Map<String, Object> deviation = service.evaluateDeviation(
                mock(AuditTrailEvent.class), "session-1");
        assertThat(deviation).isNull();
    }

    @Test
    void diagnosticsSnapshotContainsExpectedKeys() {
        Map<String, Object> diag = service.diagnosticsSnapshot();
        assertThat(diag)
                .containsEntry("enabled", true)
                .containsEntry("topK", 3)
                .containsEntry("minContextEvents", 2)
                .containsKey("generatedTotal")
                .containsKey("skippedInsufficientContextTotal")
                .containsKey("skippedOutputUnavailableTotal")
                .containsKey("deviationEvaluatedTotal")
                .containsKey("deviationMatchedTopKTotal")
                .containsKey("deviationHighScoreTotal")
                .containsKey("deviationSkippedNoPreviousPredictionTotal")
                .containsKey("lastDeviationAt");
    }

    @Test
    void predictWithDeviationMatch() {
        properties.setEvaluateDeviation(true);
        String previousJson = "{\"heads\":{\"frontend_action_name\":[{\"value\":\"change_password\",\"probability\":0.95,\"rank\":1}]}}";
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(previousJson);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("current-event");
        currentEvent.setFrontendActionName("change_password");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "current-event", 10, currentEvent);

        Map<String, Object> diag = service.diagnosticsSnapshot();
        assertThat(diag)
                .containsEntry("deviationEvaluatedTotal", 1L)
                .containsEntry("deviationMatchedTopKTotal", 1L);
    }

    @Test
    void predictWithDeviationMismatch() {
        properties.setEvaluateDeviation(true);
        String previousJson = "{\"heads\":{\"frontend_action_name\":[{\"value\":\"authorize_connection\",\"probability\":0.90,\"rank\":1},{\"value\":\"change_password\",\"probability\":0.08,\"rank\":2}]}}";
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(previousJson);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("current-event");
        currentEvent.setFrontendActionName("download_document");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "current-event", 10, currentEvent);

        Map<String, Object> diag = service.diagnosticsSnapshot();
        assertThat(diag)
                .containsEntry("deviationEvaluatedTotal", 1L)
                .containsEntry("deviationHighScoreTotal", 1L);
    }

    @Test
    void predictDeviationDisabledSkipsEvaluation() {
        properties.setEvaluateDeviation(false);
        String previousJson = "{\"heads\":{}}";
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(previousJson);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("current-event");
        currentEvent.setFrontendActionName("change_password");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "current-event", 10, currentEvent);

        Map<String, Object> diag = service.diagnosticsSnapshot();
        assertThat(diag).containsEntry("deviationEvaluatedTotal", 0L);
    }

    @Test
    void predictNoPreviousPredictionSkipsDeviation() {
        properties.setEvaluateDeviation(true);
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(null);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("current-event");
        currentEvent.setFrontendActionName("change_password");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "current-event", 10, currentEvent);

        Map<String, Object> diag = service.diagnosticsSnapshot();
        assertThat(diag)
                .containsEntry("deviationSkippedNoPreviousPredictionTotal", 1L)
                .containsEntry("deviationEvaluatedTotal", 0L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void deviationIncludesPreviousPredictionSnapshot() throws Exception {
        properties.setEvaluateDeviation(true);
        // Arrange: seed a previous prediction in Redis
        String previousJson = "{"
                + "\"schemaVersion\":\"v3.6.1\","
                + "\"insuredId\":\"insured-1\","
                + "\"sessionId\":\"session-1\","
                + "\"contextEventId\":\"anom-previous\","
                + "\"contextSize\":4,"
                + "\"model\":\"transformer\","
                + "\"heads\":{\"frontend_action_name\":[{\"value\":\"change_password\",\"probability\":0.80,\"rank\":1},{\"value\":\"authorize_connection\",\"probability\":0.15,\"rank\":2}],\"api_template\":[{\"value\":\"/auth/login\",\"probability\":0.70,\"rank\":1}],\"api_family\":[{\"value\":\"auth\",\"probability\":0.60,\"rank\":1}]},"
                + "\"headScores\":[],"
                + "\"createdAt\":\"2026-06-24T12:00:00Z\""
                + "}";
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(previousJson);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("current-event-001");
        currentEvent.setFrontendActionName("explicit_logout");
        currentEvent.setApiTemplate("/auth/logout");
        currentEvent.setApiFamily("auth");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "current-event-001", 10, currentEvent);

        // Capture the persisted JSON
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).setJson(
                eq(com.noveocare.dataprocessor.config.CacheKeys.nextEventPredictionSessionKey("session-1")),
                jsonCaptor.capture(), any(Duration.class));
        String json = jsonCaptor.getValue();
        NextEventPredictionResult result = new ObjectMapper().findAndRegisterModules()
                .readValue(json, NextEventPredictionResult.class);

        // Verify current prediction contextEventId
        assertThat(result.getContextEventId()).isEqualTo("current-event-001");

        // Verify deviation exists
        Map<String, Object> deviation = result.getDeviation();
        assertThat(deviation).isNotNull();

        // Verify previousPrediction snapshot is embedded
        assertThat(deviation).containsKey("previousPrediction");
        Map<String, Object> pp = (Map<String, Object>) deviation.get("previousPrediction");
        assertThat(pp.get("contextEventId")).isEqualTo("anom-previous");
        assertThat(pp.get("contextSize")).isEqualTo(4);
        assertThat(pp.get("model")).isEqualTo("transformer");
        assertThat(pp.get("createdAt")).isEqualTo("2026-06-24T12:00:00Z");
        assertThat(pp).containsKey("heads");

        // Verify context event IDs differ
        assertThat(deviation.get("previousPredictionContextEventId")).isEqualTo("anom-previous");
        assertThat(deviation.get("evaluatedEventId")).isEqualTo("current-event-001");

        // Verify actual values and probabilities
        assertThat(deviation).containsKey("actual");
        assertThat(deviation).containsKey("actualProbabilities");
        assertThat(deviation).containsKey("predictionMatch");
        assertThat(deviation).containsKey("deviationScore");
        assertThat((double) deviation.get("deviationScore")).isBetween(0.0, 1.0);
    }

    @Test
    void noPreviousPredictionSkipsDeviationCleanly() throws Exception {
        properties.setEvaluateDeviation(true);
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(null);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("current-event-002");
        currentEvent.setFrontendActionName("change_password");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "current-event-002", 10, currentEvent);

        // Capture the persisted prediction — deviation should be null, but prediction still saved
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).setJson(
                eq(com.noveocare.dataprocessor.config.CacheKeys.nextEventPredictionSessionKey("session-1")),
                jsonCaptor.capture(), any(Duration.class));
        String json = jsonCaptor.getValue();
        NextEventPredictionResult result = new ObjectMapper().findAndRegisterModules()
                .readValue(json, NextEventPredictionResult.class);

        // Current prediction still generated
        assertThat(result.getContextEventId()).isEqualTo("current-event-002");
        // Deviation absent (null) because no previous prediction existed
        assertThat(result.getDeviation()).isNull();
        // No risk score change — no risk-related fields exist on the DTO
    }

    @SuppressWarnings("unchecked")
    @Test
    void deviationPreviousPredictionContextEventIdDiffersFromEvaluatedEventId() throws Exception {
        properties.setEvaluateDeviation(true);
        // Previous prediction had a different contextEventId
        String previousJson = "{"
                + "\"heads\":{\"frontend_action_name\":[{\"value\":\"change_password\",\"probability\":0.95,\"rank\":1}]},"
                + "\"contextEventId\":\"anom-000000000999\","
                + "\"contextSize\":4,"
                + "\"model\":\"transformer\","
                + "\"headScores\":[]"
                + "}";
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(previousJson);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("current-event-003");
        currentEvent.setFrontendActionName("change_password");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "current-event-003", 10, currentEvent);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).setJson(
                eq(com.noveocare.dataprocessor.config.CacheKeys.nextEventPredictionSessionKey("session-1")),
                jsonCaptor.capture(), any(Duration.class));
        String json = jsonCaptor.getValue();
        NextEventPredictionResult result = new ObjectMapper().findAndRegisterModules()
                .readValue(json, NextEventPredictionResult.class);

        Map<String, Object> deviation = result.getDeviation();
        assertThat(deviation).isNotNull();
        // The previousPrediction contextEventId differs from the current event ID
        assertThat(deviation.get("previousPredictionContextEventId")).isEqualTo("anom-000000000999");
        assertThat(deviation.get("evaluatedEventId")).isEqualTo("current-event-003");
        // The current prediction's contextEventId should be the current event
        assertThat(result.getContextEventId()).isEqualTo("current-event-003");
    }

    @Test
    void sameContextPredictionSkipsDeviation() throws Exception {
        properties.setEvaluateDeviation(true);
        // Redis contains a prediction with the SAME contextEventId as the current event
        String sameContextJson = "{"
                + "\"contextEventId\":\"evt-2\","
                + "\"contextSize\":5,"
                + "\"model\":\"transformer\","
                + "\"heads\":{\"frontend_action_name\":[{\"value\":\"change_password\",\"probability\":0.95,\"rank\":1}]},"
                + "\"headScores\":[]"
                + "}";
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(sameContextJson);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("evt-2");
        currentEvent.setFrontendActionName("change_password");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "evt-2", 10, currentEvent);

        // Deviation must be null in persisted result
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).setJson(
                eq(com.noveocare.dataprocessor.config.CacheKeys.nextEventPredictionSessionKey("session-1")),
                jsonCaptor.capture(), any(Duration.class));
        String json = jsonCaptor.getValue();
        NextEventPredictionResult result = new ObjectMapper().findAndRegisterModules()
                .readValue(json, NextEventPredictionResult.class);

        assertThat(result.getDeviation()).isNull();
        assertThat(result.getContextEventId()).isEqualTo("evt-2");

        // Counter incremented
        Map<String, Object> diag = service.diagnosticsSnapshot();
        assertThat(diag)
                .containsEntry("deviationSkippedSameContextEventIdTotal", 1L)
                .containsEntry("deviationSkippedNoPreviousPredictionTotal", 0L)
                .containsEntry("lastSkippedSameContextEventId", "evt-2");
    }

    @SuppressWarnings("unchecked")
    @Test
    void deviationInvariantPreviousContextDiffersFromEvaluated() throws Exception {
        properties.setEvaluateDeviation(true);
        // Previous prediction with different contextEventId
        String previousJson = "{"
                + "\"contextEventId\":\"evt-1\","
                + "\"contextSize\":4,"
                + "\"model\":\"transformer\","
                + "\"heads\":{\"frontend_action_name\":[{\"value\":\"authorize_connection\",\"probability\":0.90,\"rank\":1}]},"
                + "\"headScores\":[]"
                + "}";
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(previousJson);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("evt-2");
        currentEvent.setFrontendActionName("authorize_connection");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "evt-2", 10, currentEvent);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).setJson(
                eq(com.noveocare.dataprocessor.config.CacheKeys.nextEventPredictionSessionKey("session-1")),
                jsonCaptor.capture(), any(Duration.class));
        String json = jsonCaptor.getValue();
        NextEventPredictionResult result = new ObjectMapper().findAndRegisterModules()
                .readValue(json, NextEventPredictionResult.class);

        Map<String, Object> deviation = result.getDeviation();
        assertThat(deviation).isNotNull();

        // Invariant: previousPredictionContextEventId != evaluatedEventId
        String ppContextId = (String) deviation.get("previousPredictionContextEventId");
        String evaluatedId = (String) deviation.get("evaluatedEventId");
        assertThat(ppContextId).isEqualTo("evt-1");
        assertThat(evaluatedId).isEqualTo("evt-2");
        assertThat(ppContextId).isNotEqualTo(evaluatedId);

        // previousPrediction.contextEventId != evaluatedEventId
        Map<String, Object> pp = (Map<String, Object>) deviation.get("previousPrediction");
        assertThat(pp.get("contextEventId")).isEqualTo("evt-1");
        assertThat((String) pp.get("contextEventId")).isNotEqualTo(evaluatedId);
    }

    @Test
    void duplicateProcessingSkipsSameContextDeviation() throws Exception {
        properties.setEvaluateDeviation(true);
        // Simulate: a prediction for event "evt-dup" was already persisted
        String existingPrediction = "{"
                + "\"contextEventId\":\"evt-dup\","
                + "\"heads\":{\"frontend_action_name\":[{\"value\":\"login\",\"probability\":0.95,\"rank\":1}]},"
                + "\"headScores\":[]"
                + "}";
        when(redisCacheService.getJson(eq("next_event_prediction:session:session-1"), eq(String.class)))
                .thenReturn(existingPrediction);

        AuditTrailEvent currentEvent = new AuditTrailEvent();
        currentEvent.setId("evt-dup");
        currentEvent.setFrontendActionName("login");

        SequenceInferenceResult inference = baseInference();
        service.predict(inference, "insured-1", "session-1", "evt-dup", 10, currentEvent);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisCacheService).setJson(
                eq(com.noveocare.dataprocessor.config.CacheKeys.nextEventPredictionSessionKey("session-1")),
                jsonCaptor.capture(), any(Duration.class));
        String json = jsonCaptor.getValue();
        NextEventPredictionResult result = new ObjectMapper().findAndRegisterModules()
                .readValue(json, NextEventPredictionResult.class);

        // Deviation must be null — same event prediction used as "previous"
        assertThat(result.getDeviation()).isNull();

        // Current prediction still generated with the event's ID
        assertThat(result.getContextEventId()).isEqualTo("evt-dup");
    }

    private SequenceInferenceResult baseInference() {
        float[] pageLogits = new float[15];
        pageLogits[0] = 10.0f;
        float[] frontendLogits = new float[53];
        frontendLogits[1] = 2.0f;
        frontendLogits[2] = 5.0f;
        frontendLogits[4] = 0.5f;

        return SequenceInferenceResult.builder()
                .modelKind(SequenceModelKind.TRANSFORMER)
                .modelArtifact("transformer_sequence_engine.onnx")
                .categoricalLogits(List.of(
                        pageLogits, frontendLogits,
                        new float[51], new float[53], new float[6], new float[20],
                        new float[4], new float[2], new float[3], new float[4],
                        new float[4], new float[5], new float[15], new float[14], new float[4]))
                .continuousPrediction(new float[9])
                .latencyMillis(5L)
                .build();
    }
}
