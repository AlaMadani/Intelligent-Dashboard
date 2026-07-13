package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmEvidenceReadServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private LlmEvidenceReadService service;

    @BeforeEach
    void setUp() {
        service = new LlmEvidenceReadService(redisReadService, anomalyEventRepository, sessionAnalysisRepository, objectMapper);
        ReflectionTestUtils.setField(service, "evidenceRehydrateTtlHours", 24L);
    }

    @Test
    void evidencePrefersRedisPayload() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-1\"}");
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-1"))).thenReturn(Optional.of(payload));

        Optional<JsonNode> result = service.readEvidence("evt-1");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().path("eventId").asText()).isEqualTo("evt-1");
        verify(redisReadService, never()).writeJson(any(), any(), any());
    }

    @Test
    void evidenceFallsBackToSqlSessionPayloadAndRehydratesRedis() {
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-2"))).thenReturn(Optional.empty());
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-2");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("session-1");
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-2")).thenReturn(Optional.of(anomaly));
        SessionAnalysis session = new SessionAnalysis();
        session.setLlmExplanationEvidencePayloadJson("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-2\"}");
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "session-1"))
                .thenReturn(Optional.of(session));

        Optional<JsonNode> result = service.readEvidence("evt-2");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().path("schemaVersion").asText()).isEqualTo("v3.6.1");
        verify(redisReadService).writeJson(eq(CacheKeys.alertLlmEvidenceKey("evt-2")), any(JsonNode.class), any(Duration.class));
    }

    @Test
    void malformedSqlEvidenceReturnsEmpty() {
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-3"))).thenReturn(Optional.empty());
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-3");
        anomaly.setLlmExplanationEvidencePayloadJson("{bad-json");
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-3")).thenReturn(Optional.of(anomaly));

        Optional<JsonNode> result = service.readEvidence("evt-3");

        assertThat(result).isEmpty();
    }

    @Test
    void evidenceHashUsesExistingFieldWhenPresent() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"evidenceHash\":\"abcdef1234567890abcdef1234567890\"}");

        String hash = service.evidenceHash(evidence);

        assertThat(hash).isEqualTo("abcdef1234567890abcdef1234567890");
    }

    @Test
    void evidenceHashComputesWhenFieldMissing() throws Exception {
        JsonNode evidence = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-5\"}");

        String hash = service.evidenceHash(evidence);

        assertThat(hash).hasSize(16);
        assertThat(hash).isAlphanumeric();
    }

    @Test
    void bothEvidenceSourcesMissingReturnsEmpty() {
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-6"))).thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-6")).thenReturn(Optional.empty());

        Optional<JsonNode> result = service.readEvidence("evt-6");

        assertThat(result).isEmpty();
    }

    @Test
    void redisEvidenceExactMatchAccepted() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"eventId\":\"evt-7\",\"recordId\":\"evt-7\",\"schemaVersion\":\"v3.6.1\"}");
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-7"))).thenReturn(Optional.of(payload));

        Optional<JsonNode> result = service.readEvidence("evt-7");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().path("eventId").asText()).isEqualTo("evt-7");
    }

    @Test
    void redisEvidenceMismatchedRejected() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"eventId\":\"evt-other\",\"eventMetadata\":{\"eventId\":\"evt-other\"}}");
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-8"))).thenReturn(Optional.of(payload));

        Optional<JsonNode> result = service.readEvidence("evt-8");

        assertThat(result).isEmpty();
    }

    @Test
    void redisEvidenceConflictRejected() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"eventId\":\"evt-9\",\"eventMetadata\":{\"eventId\":\"evt-other\"}}");
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-9"))).thenReturn(Optional.of(payload));

        Optional<JsonNode> result = service.readEvidence("evt-9");

        assertThat(result).isEmpty();
    }

    @Test
    void sqlEvidenceMismatchedRejected() throws Exception {
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-10"))).thenReturn(Optional.empty());
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-10");
        anomaly.setLlmExplanationEvidencePayloadJson("{\"eventId\":\"evt-wrong\",\"eventMetadata\":{\"eventId\":\"evt-wrong\"}}");
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-10")).thenReturn(Optional.of(anomaly));

        Optional<JsonNode> result = service.readEvidence("evt-10");

        assertThat(result).isEmpty();
    }

    @Test
    void sqlSessionEvidenceMismatchedRejected() throws Exception {
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-11"))).thenReturn(Optional.empty());
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-11");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("session-11");
        // Anomaly has no evidence payload
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-11")).thenReturn(Optional.of(anomaly));
        SessionAnalysis session = new SessionAnalysis();
        session.setLlmExplanationEvidencePayloadJson("{\"eventId\":\"evt-wrong-session\",\"eventMetadata\":{\"eventId\":\"evt-wrong-session\"}}");
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "session-11"))
                .thenReturn(Optional.of(session));

        Optional<JsonNode> result = service.readEvidence("evt-11");

        assertThat(result).isEmpty();
    }

    @Test
    void sqlSessionEvidenceExactMatchAccepted() throws Exception {
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-12"))).thenReturn(Optional.empty());
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-12");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("session-12");
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-12")).thenReturn(Optional.of(anomaly));
        SessionAnalysis session = new SessionAnalysis();
        session.setLlmExplanationEvidencePayloadJson("{\"eventId\":\"evt-12\",\"eventMetadata\":{\"eventId\":\"evt-12\"}}");
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "session-12"))
                .thenReturn(Optional.of(session));

        Optional<JsonNode> result = service.readEvidence("evt-12");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().path("eventId").asText()).isEqualTo("evt-12");
    }
}
