package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.StatsResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisDashboardRefreshListenerTest {

    private final LiveStatsStreamService liveStatsStreamService = mock(LiveStatsStreamService.class);
    private final StatsService statsService = mock(StatsService.class);
    private final RedisDashboardRefreshListener listener = new RedisDashboardRefreshListener(
            new ObjectMapper().findAndRegisterModules(),
            liveStatsStreamService,
            statsService
    );

    @Test
    void forecastRefreshPreservesLegacyTargetAndBroadcastsV36Alias() {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("{\"refresh\":\"forecasts\"}".getBytes(StandardCharsets.UTF_8));

        listener.onMessage(message, null);

        verify(liveStatsStreamService).broadcastRefresh("forecasts");
        verify(liveStatsStreamService).broadcastRefresh("forecast");
    }

    @Test
    void statsRefreshStillPushesStatsSnapshot() {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("{\"refresh\":\"stats\"}".getBytes(StandardCharsets.UTF_8));
        when(statsService.getLiveStats(any(LocalDate.class)))
                .thenReturn(new StatsResponseDto(LocalDate.now(), "missing", new ObjectMapper().createObjectNode()));

        listener.onMessage(message, null);

        verify(liveStatsStreamService).broadcastStats(any(StatsResponseDto.class));
        verify(liveStatsStreamService).broadcastRefresh("stats");
    }
}
