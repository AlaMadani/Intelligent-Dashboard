package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto.V36NextEventPredictionDeviationDto;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto.V36NextEventPredictionHeadItemDto;
import com.neo.dashboard.entity.NextEventPrediction;
import com.neo.dashboard.repository.NextEventPredictionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V36NextEventPredictionServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final NextEventPredictionRepository repository = mock(NextEventPredictionRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private V36NextEventPredictionService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service = new V36NextEventPredictionService(redisReadService, repository, objectMapper, transactionManager);
        service.initTransactionTemplate();
    }

    @Test
    void getBySessionIdRedisHit() {
        V36NextEventPredictionDto expected = validPrediction();
        when(redisReadService.readValue("next_event_prediction:session:sess-1", V36NextEventPredictionDto.class))
                .thenReturn(Optional.of(expected));

        V36NextEventPredictionDto result = service.getBySessionId("sess-1");

        assertThat(result.getSessionId()).isEqualTo("sess-1");
        assertThat(result.getSource()).isEqualTo("redis");
        assertThat(result.getHeads()).containsKey("api_family");
    }

    @Test
    void getBySessionIdSqlFallback() throws Exception {
        when(redisReadService.readValue(anyString(), any(Class.class))).thenReturn(Optional.empty());
        String predictionsJson = objectMapper.writeValueAsString(Map.of(
                "heads", Map.of("api_family", List.of(Map.of("value", "documents", "probability", 0.63, "rank", 1)))
        ));
        NextEventPrediction entity = predictionEntity("sess-1", "insured-1", predictionsJson, null);
        when(repository.findTopBySessionIdOrderByCreatedAtDesc("sess-1"))
                .thenReturn(Optional.of(entity));

        V36NextEventPredictionDto result = service.getBySessionId("sess-1");

        assertThat(result.getSessionId()).isEqualTo("sess-1");
        assertThat(result.getSource()).isEqualTo("sql_fallback");
        assertThat(result.getHeads()).isNotEmpty();
    }

    @Test
    void getBySessionIdNoPrediction() {
        when(redisReadService.readValue(anyString(), any(Class.class))).thenReturn(Optional.empty());
        when(repository.findTopBySessionIdOrderByCreatedAtDesc("sess-1"))
                .thenReturn(Optional.empty());

        V36NextEventPredictionDto result = service.getBySessionId("sess-1");

        assertThat(result.getHeads()).isNull();
        assertThat(result.getWarnings()).contains("next_event_prediction_not_available");
    }

    @Test
    void getByInsuredIdRedisHit() {
        V36NextEventPredictionDto expected = validPrediction();
        when(redisReadService.readValue("next_event_prediction:insured:insured-1", V36NextEventPredictionDto.class))
                .thenReturn(Optional.of(expected));

        V36NextEventPredictionDto result = service.getByInsuredId("insured-1");

        assertThat(result.getSource()).isEqualTo("redis");
    }

    @Test
    void getBestForUser360PrefersSession() {
        V36NextEventPredictionDto sessionPrediction = validPrediction();
        when(redisReadService.readValue("next_event_prediction:session:sess-1", V36NextEventPredictionDto.class))
                .thenReturn(Optional.of(sessionPrediction));

        V36NextEventPredictionDto result = service.getBestForUser360("insured-1", "sess-1");

        assertThat(result.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    void getBestForUser360FallsBackToInsured() {
        when(redisReadService.readValue("next_event_prediction:session:sess-1", V36NextEventPredictionDto.class))
                .thenReturn(Optional.empty());
        when(repository.findTopBySessionIdOrderByCreatedAtDesc("sess-1"))
                .thenReturn(Optional.empty());

        V36NextEventPredictionDto insuredPrediction = validPrediction();
        insuredPrediction.setInsuredId("insured-1");
        when(redisReadService.readValue("next_event_prediction:insured:insured-1", V36NextEventPredictionDto.class))
                .thenReturn(Optional.of(insuredPrediction));

        V36NextEventPredictionDto result = service.getBestForUser360("insured-1", "sess-1");

        assertThat(result.getInsuredId()).isEqualTo("insured-1");
        assertThat(result.getWarnings()).contains("fallback_to_insured_prediction");
    }

    @Test
    void getBestForUser360NoPrediction() {
        when(redisReadService.readValue(anyString(), any(Class.class))).thenReturn(Optional.empty());
        when(repository.findTopBySessionIdOrderByCreatedAtDesc(anyString())).thenReturn(Optional.empty());
        when(repository.findTopByInsuredIdOrderByCreatedAtDesc(anyString())).thenReturn(Optional.empty());

        V36NextEventPredictionDto result = service.getBestForUser360("insured-1", "sess-1");

        assertThat(result.getHeads()).isNull();
        assertThat(result.getWarnings()).contains("next_event_prediction_not_available");
    }

    @Test
    void sessionIdNullReturnsUnavailable() {
        V36NextEventPredictionDto result = service.getBySessionId(null);
        assertThat(result.getWarnings()).contains("session_id_missing");
    }

    @Test
    void deviationParsedFromPredictionsJson() throws Exception {
        String predictionsJson = objectMapper.writeValueAsString(Map.of(
                "heads", Map.of("api_family", List.of(Map.of("value", "documents", "probability", 0.63, "rank", 1))),
                "deviation", Map.of("actual", Map.of("api_family", "auth"), "deviationScore", 0.85)
        ));
        NextEventPrediction entity = predictionEntity("sess-1", "insured-1", predictionsJson, null);

        when(redisReadService.readValue(anyString(), any(Class.class))).thenReturn(Optional.empty());
        when(repository.findTopBySessionIdOrderByCreatedAtDesc("sess-1"))
                .thenReturn(Optional.of(entity));

        V36NextEventPredictionDto result = service.getBySessionId("sess-1");

        assertThat(result.getDeviation()).isNotNull();
        assertThat(result.getDeviation().getDeviationScore()).isEqualTo(0.85);
    }

    @Test
    void deviationParsedFromPredictionsJsonIncludesPreviousPrediction() throws Exception {
        Map<String, Object> previousPrediction = new LinkedHashMap<>();
        previousPrediction.put("contextEventId", "event-before");
        previousPrediction.put("heads", Map.of("api_family", List.of(Map.of("value", "documents", "probability", 0.63, "rank", 1))));

        Map<String, Object> deviation = new LinkedHashMap<>();
        deviation.put("actual", Map.of("api_family", "auth"));
        deviation.put("deviationScore", 0.85);
        deviation.put("previousPrediction", previousPrediction);
        deviation.put("previousPredictionContextEventId", "event-before");
        deviation.put("evaluatedEventId", "event-current");

        String predictionsJson = objectMapper.writeValueAsString(Map.of(
                "heads", Map.of("api_family", List.of(Map.of("value", "documents", "probability", 0.63, "rank", 1))),
                "deviation", deviation
        ));
        NextEventPrediction entity = predictionEntity("sess-1", "insured-1", predictionsJson, null);

        when(redisReadService.readValue(anyString(), any(Class.class))).thenReturn(Optional.empty());
        when(repository.findTopBySessionIdOrderByCreatedAtDesc("sess-1"))
                .thenReturn(Optional.of(entity));

        V36NextEventPredictionDto result = service.getBySessionId("sess-1");

        assertThat(result.getDeviation()).isNotNull();
        assertThat(result.getDeviation().getPreviousPrediction()).isNotNull();
        assertThat(result.getDeviation().getPreviousPrediction())
                .containsEntry("contextEventId", "event-before");
        assertThat(result.getDeviation().getPreviousPredictionContextEventId()).isEqualTo("event-before");
        assertThat(result.getDeviation().getEvaluatedEventId()).isEqualTo("event-current");
        assertThat(result.getDeviation().getDeviationScore()).isEqualTo(0.85);
    }

    @Test
    void deviationPreviousPredictionPreservesUnknownFields() throws Exception {
        Map<String, Object> previousPrediction = new LinkedHashMap<>();
        previousPrediction.put("contextEventId", "event-before");
        previousPrediction.put("extraField", "should-survive");
        previousPrediction.put("nested", Map.of("key", "value"));

        Map<String, Object> deviation = new LinkedHashMap<>();
        deviation.put("actual", Map.of("api_family", "auth"));
        deviation.put("deviationScore", 0.85);
        deviation.put("previousPrediction", previousPrediction);
        deviation.put("unknownDeviationField", "also-survives");

        String predictionsJson = objectMapper.writeValueAsString(Map.of(
                "heads", Map.of("api_family", List.of(Map.of("value", "documents", "probability", 0.63, "rank", 1))),
                "deviation", deviation
        ));
        NextEventPrediction entity = predictionEntity("sess-1", "insured-1", predictionsJson, null);

        when(redisReadService.readValue(anyString(), any(Class.class))).thenReturn(Optional.empty());
        when(repository.findTopBySessionIdOrderByCreatedAtDesc("sess-1"))
                .thenReturn(Optional.of(entity));

        V36NextEventPredictionDto result = service.getBySessionId("sess-1");

        assertThat(result.getDeviation()).isNotNull();
        assertThat(result.getDeviation().getPreviousPrediction()).isNotNull();
        assertThat(result.getDeviation().getPreviousPrediction())
                .containsEntry("extraField", "should-survive");
        assertThat(result.getDeviation().getPreviousPrediction())
                .containsKey("nested");
    }

    @Test
    void deviationMissingPreviousPredictionStillWorks() throws Exception {
        String predictionsJson = objectMapper.writeValueAsString(Map.of(
                "heads", Map.of("api_family", List.of(Map.of("value", "documents", "probability", 0.63, "rank", 1))),
                "deviation", Map.of("actual", Map.of("api_family", "auth"), "deviationScore", 0.85)
        ));
        NextEventPrediction entity = predictionEntity("sess-1", "insured-1", predictionsJson, null);

        when(redisReadService.readValue(anyString(), any(Class.class))).thenReturn(Optional.empty());
        when(repository.findTopBySessionIdOrderByCreatedAtDesc("sess-1"))
                .thenReturn(Optional.of(entity));

        V36NextEventPredictionDto result = service.getBySessionId("sess-1");

        assertThat(result.getDeviation()).isNotNull();
        assertThat(result.getDeviation().getPreviousPrediction()).isNull();
        assertThat(result.getDeviation().getPreviousPredictionContextEventId()).isNull();
        assertThat(result.getDeviation().getEvaluatedEventId()).isNull();
        assertThat(result.getDeviation().getDeviationScore()).isEqualTo(0.85);
    }

    @Test
    void deviationAbsentReturnsNull() throws Exception {
        String predictionsJson = objectMapper.writeValueAsString(Map.of(
                "heads", Map.of("api_family", List.of(Map.of("value", "documents", "probability", 0.63, "rank", 1)))
        ));
        NextEventPrediction entity = predictionEntity("sess-1", "insured-1", predictionsJson, null);

        when(redisReadService.readValue(anyString(), any(Class.class))).thenReturn(Optional.empty());
        when(repository.findTopBySessionIdOrderByCreatedAtDesc("sess-1"))
                .thenReturn(Optional.of(entity));

        V36NextEventPredictionDto result = service.getBySessionId("sess-1");

        assertThat(result.getDeviation()).isNull();
    }

    @Test
    void redisHitAvoidsSqlCall() {
        V36NextEventPredictionDto expected = validPrediction();
        when(redisReadService.readValue("next_event_prediction:session:sess-1", V36NextEventPredictionDto.class))
                .thenReturn(Optional.of(expected));

        service.getBySessionId("sess-1");

        verify(repository, never()).findTopBySessionIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void noUnexpectedRollbackWhenSqlFails() {
        when(redisReadService.readValue(anyString(), any(Class.class))).thenReturn(Optional.empty());
        when(repository.findTopBySessionIdOrderByCreatedAtDesc("sess-1"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Simulated SQL failure"));

        V36NextEventPredictionDto result = service.getBySessionId("sess-1");

        assertThat(result.getWarnings()).contains("next_event_prediction_not_available");
    }

    private static V36NextEventPredictionDto validPrediction() {
        V36NextEventPredictionDto dto = new V36NextEventPredictionDto();
        dto.setSessionId("sess-1");
        dto.setInsuredId("insured-1");
        dto.setContextEventId("evt-1");
        dto.setContextSize(5);
        dto.setModel("transformer");
        dto.setHeads(Map.of(
                "api_family", List.of(new V36NextEventPredictionHeadItemDto("documents", 0.63, 1)),
                "frontend_action_name", List.of(new V36NextEventPredictionHeadItemDto("download_document", 0.42, 1))
        ));
        dto.setCreatedAt(Instant.now());
        return dto;
    }

    private static NextEventPrediction predictionEntity(String sessionId, String insuredId,
                                                         String predictionsJson, String unused) {
        NextEventPrediction e = new NextEventPrediction();
        e.setId(1L);
        e.setSessionId(sessionId);
        e.setInsuredId(insuredId);
        e.setContextEventId("evt-1");
        e.setContextSize(5);
        e.setModelName("transformer");
        e.setPredictionsJson(predictionsJson);
        e.setCreatedAt(Instant.now());
        return e;
    }
}
