package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RedisPubSubProperties;
import com.noveocare.dataprocessor.dto.NextActionScore;
import com.noveocare.dataprocessor.dto.PathDeviationResult;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.AnomalyEventRepository;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import com.noveocare.dataprocessor.ai.RuntimeArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DashboardSnapshotServiceTest {

    private final RedisCacheService redisCacheService = mock(RedisCacheService.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);
    private final RuntimeArtifactService runtimeArtifactService = mock(RuntimeArtifactService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final StatisticsService statisticsService = mock(StatisticsService.class);

    private DashboardSnapshotService service;

    @BeforeEach
    void setUp() {
        RedisCacheProperties cacheProperties = new RedisCacheProperties();
        cacheProperties.setSessionInsight(Duration.ofHours(2));

        service = new DashboardSnapshotService(
                redisCacheService,
                cacheProperties,
                sessionAnalysisRepository,
                anomalyEventRepository,
                runtimeArtifactService,
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                statisticsService,
                new RedisPubSubProperties()
        );
    }

    @Test
    void cacheSessionInsightPublishesExpandedWorkerContract() {
        SessionSummary summary = SessionSummary.builder()
                .insuredId("insured-1")
                .sessionId("session-1")
                .month("APRIL")
                .sessionNumber(3)
                .sessionStart(Instant.parse("2026-04-27T09:00:00Z"))
                .sessionEnd(Instant.parse("2026-04-27T09:10:00Z"))
                .totalEvents(12)
                .totalDurationSeconds(600L)
                .uniqueActions(5)
                .uniqueRoutes(4)
                .uniqueIpsUsed(2)
                .uniqueDevicesUsed(1)
                .totalKOs(2)
                .totalOKs(10)
                .longestKoStreak(2)
                .hasLogin(1)
                .hasLogout(0)
                .ipChanged(1)
                .deviceChanged(0)
                .totalDownloadActions(3)
                .maxDownloadsIn2Minutes(2)
                .pingPongCount(1)
                .riskScoreMax(87.0)
                .riskScoreAvg(45.0)
                .anomalyEventCount(1)
                .anomalyTypes(List.of("geo_jump"))
                .campaignIds(List.of("campaign-1"))
                .actionCounts(Map.of("LOGIN", 1L, "DOWNLOAD", 3L))
                .actionSequenceSignature("sig-a")
                .routeSequenceSignature("sig-r")
                .build();
        SessionInsight insight = SessionInsight.builder()
                .binaryAnomaly(true)
                .anomaly(true)
                .anomalyScore(0.91)
                .anomalyProbability(0.88)
                .binaryDetectorArtifact("xgb_binary")
                .anomalyType("geo_jump")
                .anomalyTypeConfidence(0.83)
                .churnProbability(0.12)
                .ensembleRiskScore(77.0)
                .personaCluster(2)
                .riskLevel("HIGH")
                .pathDeviation(PathDeviationResult.builder()
                        .deviated(true)
                        .fromAction("LOGIN")
                        .toAction("DOWNLOAD")
                        .transitionProbability(0.01)
                        .build())
                .nextActions(List.of(NextActionScore.builder().action("VERIFY").probability(0.75).build()))
                .computedAt(Instant.parse("2026-04-27T09:05:00Z"))
                .build();

        service.cacheSessionInsight(summary, insight);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisCacheService).setJson(anyString(), payloadCaptor.capture(), any(Duration.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("binaryDetectorArtifact", "xgb_binary")
                .containsEntry("typeConfidence", 0.83)
                .containsEntry("ensembleRiskScore", 77.0)
                .containsEntry("pathDeviationFlag", true)
                .containsEntry("transitionFromAction", "LOGIN")
                .containsEntry("totalKOs", 2)
                .containsEntry("totalDownloadActions", 3);
    }
}
