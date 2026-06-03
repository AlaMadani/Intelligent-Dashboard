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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
    }

    @Test
    void evidencePrefersRedisPayload() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-1\"}");
        when(redisReadService.readJson(CacheKeys.alertLlmEvidenceKey("evt-1"))).thenReturn(Optional.of(payload));

        Optional<JsonNode> result = service.readEvidence("evt-1");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().path("eventId").asText()).isEqualTo("evt-1");
    }

    @Test
    void evidenceFallsBackToSqlSessionPayload() {
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
}
