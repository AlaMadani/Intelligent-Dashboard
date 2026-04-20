package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.AnomalyExplanationDto;
import com.neo.dashboard.dto.StatsResponseDto;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.mapper.SessionAnalysisMapper;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for anomaly explanation source selection.
 */
class AnomalyExplanationServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
    private final RiskProfileService riskProfileService = mock(RiskProfileService.class);
    private final NextActionPredictionService nextActionPredictionService = mock(NextActionPredictionService.class);
    private final ActiveAnomalyService activeAnomalyService = mock(ActiveAnomalyService.class);
    private final SessionInsightReadService sessionInsightReadService = mock(SessionInsightReadService.class);
    private final StatsService statsService = mock(StatsService.class);
    private final SessionAnalysisMapper sessionAnalysisMapper = Mappers.getMapper(SessionAnalysisMapper.class);

    private AnomalyExplanationService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        doNothing().when(valueOperations).set(anyString(), anyString(), Mockito.any());
        when(riskProfileService.getRiskProfile(anyString())).thenReturn(Optional.empty());
        when(nextActionPredictionService.getPrediction(anyString())).thenReturn(Optional.empty());
        when(activeAnomalyService.getActiveAnomaly(anyString())).thenReturn(Optional.empty());
        when(statsService.getLiveStats(Mockito.any(LocalDate.class)))
                .thenReturn(new StatsResponseDto(LocalDate.now(), "missing", JsonNodeFactory.instance.objectNode()));
        when(statsService.getTrendStats(Mockito.any(LocalDate.class)))
                .thenReturn(new StatsResponseDto(LocalDate.now(), "missing", JsonNodeFactory.instance.objectNode()));
        service = new AnomalyExplanationService(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                anomalyEventRepository,
                sessionAnalysisRepository,
                sessionAnalysisMapper,
                riskProfileService,
                nextActionPredictionService,
                activeAnomalyService,
                sessionInsightReadService,
                statsService
        );
    }

    @Test
    void explainPrefersSessionAnalysisTextBeforeLiveInsight() {
        AnomalyEvent anomalyEvent = new AnomalyEvent();
        anomalyEvent.setId(77L);
        anomalyEvent.setInsuredId("insured-1");
        anomalyEvent.setSessionId("session-1");
        anomalyEvent.setEventJson("{\"explainabilityText\":\"context fallback\"}");
        SessionAnalysis sessionAnalysis = new SessionAnalysis();
        sessionAnalysis.setExplainabilityText("durable sql explanation");
        when(anomalyEventRepository.findById(77L)).thenReturn(Optional.of(anomalyEvent));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "session-1"))
                .thenReturn(Optional.of(sessionAnalysis));
        when(sessionInsightReadService.getInsight("insured-1", "session-1"))
                .thenReturn(Optional.of(new ObjectMapper().createObjectNode().put("explainabilityText", "live session explanation")));

        Optional<AnomalyExplanationDto> result = service.explain(77L, false);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getSource()).isEqualTo("session-analysis");
        assertThat(result.orElseThrow().getExplanation()).isEqualTo("durable sql explanation");
    }
}
