package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36LiveAlertSummaryDto;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V36AlertServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);

    private V36AlertService service;

    @BeforeEach
    void setUp() {
        service = new V36AlertService(
                redisReadService,
                anomalyEventRepository,
                sessionAnalysisRepository,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void liveAlertsUseRedisAndApplyFilters() {
        V36LiveAlertSummaryDto critical = new V36LiveAlertSummaryDto();
        critical.setEventId("evt-1");
        critical.setRiskLevel("CRITICAL");
        critical.setInsuredId("insured-1");
        critical.setTimestamp(Instant.parse("2026-05-25T02:15:00Z"));
        V36LiveAlertSummaryDto high = new V36LiveAlertSummaryDto();
        high.setEventId("evt-2");
        high.setRiskLevel("HIGH");
        high.setInsuredId("insured-2");
        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(critical, high));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                "CRITICAL", null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getEventId()).isEqualTo("evt-1");
        assertThat(result.getItems().getFirst().getSchemaVersion()).isEqualTo("v3.6.1");
    }

    @Test
    void alertDetailThrowsStructuredNotFoundWhenRedisAndSqlMiss() {
        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-missing"), com.neo.dashboard.dto.v36.V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAlertDetail("evt-missing"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Alert not found");
    }
}
