package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36RuntimeHealthResponse;
import com.neo.dashboard.dto.v36.V36SecurityOverviewResponse;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V36DashboardServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final StatsService statsService = mock(StatsService.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);

    private V36DashboardService service;

    @BeforeEach
    void setUp() {
        service = new V36DashboardService(
                redisReadService,
                statsService,
                sessionAnalysisRepository,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void runtimeHealthReadsV36RedisSnapshot() {
        V36RuntimeHealthResponse response = new V36RuntimeHealthResponse();
        response.setStatus("HEALTHY");
        when(redisReadService.readValue(CacheKeys.DASHBOARD_SECURITY_OVERVIEW_V36, V36SecurityOverviewResponse.class))
                .thenReturn(Optional.empty());
        when(redisReadService.readValue(CacheKeys.AI_RUNTIME_HEALTH_V36, V36RuntimeHealthResponse.class))
                .thenReturn(Optional.of(response));

        V36RuntimeHealthResponse result = service.getRuntimeHealth();

        assertThat(result.getSchemaVersion()).isEqualTo("v3.6.1");
        assertThat(result.getStatus()).isEqualTo("HEALTHY");
    }

    @Test
    void runtimeHealthFallsBackToUnknownWhenRedisMissing() {
        when(redisReadService.readValue(CacheKeys.AI_RUNTIME_HEALTH_V36, V36RuntimeHealthResponse.class))
                .thenReturn(Optional.empty());

        V36RuntimeHealthResponse result = service.getRuntimeHealth();

        assertThat(result.getStatus()).isEqualTo("UNKNOWN");
        assertThat(result.getMessage()).contains("not available");
    }

    }
