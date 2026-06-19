package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.neo.dashboard.dto.v36.V36SecurityOverviewResponse;
import com.neo.dashboard.dto.v36.V36LiveAlertSummaryDto;
import com.neo.dashboard.entity.DashboardSnapshot;
import com.neo.dashboard.repository.DashboardSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class DashboardSnapshotFallbackServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final DashboardSnapshotRepository dashboardSnapshotRepository = mock(DashboardSnapshotRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private DashboardSnapshotFallbackService service;

    @BeforeEach
    void setUp() {
        service = new DashboardSnapshotFallbackService(redisReadService, dashboardSnapshotRepository, objectMapper);
    }

    @Test
    void redisHitReturnsRedisPayload() {
        V36SecurityOverviewResponse redisPayload = new V36SecurityOverviewResponse();
        redisPayload.setTotalEventsToday(100L);
        redisPayload.setActiveUsersToday(25L);

        when(redisReadService.readValue("dashboard:security-overview:v3_6", V36SecurityOverviewResponse.class))
                .thenReturn(Optional.of(redisPayload));

        DashboardSnapshotFallbackService.FallbackResult<V36SecurityOverviewResponse> result =
                service.readWithFallback(
                        "dashboard:security-overview:v3_6",
                        V36SecurityOverviewResponse.class,
                        "security-overview",
                        "security-overview:latest");

        assertThat(result).isNotNull();
        assertThat(result.source()).isEqualTo("redis");
        assertThat(result.payload().getTotalEventsToday()).isEqualTo(100L);
    }

    @Test
    void redisMissSqlHitReturnsSqlFallbackPayload() {
        when(redisReadService.readValue("dashboard:security-overview:v3_6", V36SecurityOverviewResponse.class))
                .thenReturn(Optional.empty());

        DashboardSnapshot snapshot = new DashboardSnapshot();
        snapshot.setViewName("security-overview");
        snapshot.setSnapshotKey("security-overview:latest");
        snapshot.setPayloadJson("{\"totalEventsToday\":200,\"activeUsersToday\":50}");

        when(dashboardSnapshotRepository.findByViewNameAndSnapshotKey("security-overview", "security-overview:latest"))
                .thenReturn(Optional.of(snapshot));

        DashboardSnapshotFallbackService.FallbackResult<V36SecurityOverviewResponse> result =
                service.readWithFallback(
                        "dashboard:security-overview:v3_6",
                        V36SecurityOverviewResponse.class,
                        "security-overview",
                        "security-overview:latest");

        assertThat(result).isNotNull();
        assertThat(result.source()).isEqualTo("sql_fallback");
        assertThat(result.payload().getTotalEventsToday()).isEqualTo(200L);
        assertThat(result.payload().getActiveUsersToday()).isEqualTo(50L);
    }

    @Test
    void redisAndSqlBothMissReturnsNull() {
        when(redisReadService.readValue("dashboard:security-overview:v3_6", V36SecurityOverviewResponse.class))
                .thenReturn(Optional.empty());
        when(dashboardSnapshotRepository.findByViewNameAndSnapshotKey("security-overview", "security-overview:latest"))
                .thenReturn(Optional.empty());

        DashboardSnapshotFallbackService.FallbackResult<V36SecurityOverviewResponse> result =
                service.readWithFallback(
                        "dashboard:security-overview:v3_6",
                        V36SecurityOverviewResponse.class,
                        "security-overview",
                        "security-overview:latest");

        assertThat(result).isNull();
    }

    @Test
    void sqlFallbackMalformedJsonReturnsNull() {
        when(redisReadService.readValue("dashboard:security-overview:v3_6", V36SecurityOverviewResponse.class))
                .thenReturn(Optional.empty());

        DashboardSnapshot snapshot = new DashboardSnapshot();
        snapshot.setViewName("security-overview");
        snapshot.setSnapshotKey("security-overview:latest");
        snapshot.setPayloadJson("{invalid-json}");

        when(dashboardSnapshotRepository.findByViewNameAndSnapshotKey("security-overview", "security-overview:latest"))
                .thenReturn(Optional.of(snapshot));

        DashboardSnapshotFallbackService.FallbackResult<V36SecurityOverviewResponse> result =
                service.readWithFallback(
                        "dashboard:security-overview:v3_6",
                        V36SecurityOverviewResponse.class,
                        "security-overview",
                        "security-overview:latest");

        assertThat(result).isNull();
    }

    @Test
    void sqlFallbackReadListHandlesArrayPayload() {
        when(dashboardSnapshotRepository.findByViewNameAndSnapshotKey("alerts", "alerts:latest"))
                .thenReturn(Optional.of(createAlertSnapshot("[{\"eventId\":\"evt-1\",\"riskLevel\":\"HIGH\"},{\"eventId\":\"evt-2\",\"riskLevel\":\"MEDIUM\"}]")));

        List<V36LiveAlertSummaryDto> result =
                service.readListFromSql(V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEventId()).isEqualTo("evt-1");
        assertThat(result.get(1).getEventId()).isEqualTo("evt-2");
    }

    @Test
    void sqlFallbackReadListHandlesItemsWrappedPayload() {
        when(dashboardSnapshotRepository.findByViewNameAndSnapshotKey("alerts", "alerts:latest"))
                .thenReturn(Optional.of(createAlertSnapshot(
                        "{\"items\":[{\"eventId\":\"evt-1\",\"riskLevel\":\"HIGH\"},{\"eventId\":\"evt-2\",\"riskLevel\":\"MEDIUM\"}]}")));

        List<V36LiveAlertSummaryDto> result =
                service.readListFromSql(V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEventId()).isEqualTo("evt-1");
    }

    @Test
    void sqlFallbackReadListHandlesEmptySql() {
        when(dashboardSnapshotRepository.findByViewNameAndSnapshotKey("alerts", "alerts:latest"))
                .thenReturn(Optional.empty());

        List<V36LiveAlertSummaryDto> result =
                service.readListFromSql(V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 10);

        assertThat(result).isEmpty();
    }

    @Test
    void rehydrateRedisWritesPayloadBack() {
        V36SecurityOverviewResponse payload = new V36SecurityOverviewResponse();
        payload.setTotalEventsToday(100L);

        service.rehydrateRedis("dashboard:security-overview:v3_6", payload, java.time.Duration.ofHours(1));

        verify(redisReadService).writeJson("dashboard:security-overview:v3_6", payload, java.time.Duration.ofHours(1));
    }

    @Test
    void deserializationConsistentBetweenRedisAndSql() throws Exception {
        String json = "{\"totalEventsToday\":150,\"activeUsersToday\":30,\"snapshotTimestamp\":\"2026-06-17T10:00:00Z\"}";

        V36SecurityOverviewResponse fromJson = objectMapper.readValue(json, V36SecurityOverviewResponse.class);

        DashboardSnapshot snapshot = new DashboardSnapshot();
        snapshot.setViewName("security-overview");
        snapshot.setSnapshotKey("security-overview:latest");
        snapshot.setPayloadJson(json);

        when(redisReadService.readValue("dashboard:security-overview:v3_6", V36SecurityOverviewResponse.class))
                .thenReturn(Optional.empty());
        when(dashboardSnapshotRepository.findByViewNameAndSnapshotKey("security-overview", "security-overview:latest"))
                .thenReturn(Optional.of(snapshot));

        DashboardSnapshotFallbackService.FallbackResult<V36SecurityOverviewResponse> sqlResult =
                service.readWithFallback(
                        "dashboard:security-overview:v3_6",
                        V36SecurityOverviewResponse.class,
                        "security-overview",
                        "security-overview:latest");

        assertThat(sqlResult).isNotNull();
        assertThat(sqlResult.payload().getTotalEventsToday()).isEqualTo(fromJson.getTotalEventsToday());
        assertThat(sqlResult.payload().getActiveUsersToday()).isEqualTo(fromJson.getActiveUsersToday());
    }

    @Test
    void securityOverviewFallbackChain() {
        when(redisReadService.readValue("dashboard:security-overview:v3_6", V36SecurityOverviewResponse.class))
                .thenReturn(Optional.empty());
        when(dashboardSnapshotRepository.findByViewNameAndSnapshotKey("security-overview", "security-overview:latest"))
                .thenReturn(Optional.empty());

        DashboardSnapshotFallbackService.FallbackResult<V36SecurityOverviewResponse> result =
                service.readWithFallback(
                        "dashboard:security-overview:v3_6",
                        V36SecurityOverviewResponse.class,
                        "security-overview",
                        "security-overview:latest");

        assertThat(result).isNull();
    }

    private DashboardSnapshot createAlertSnapshot(String payloadJson) {
        DashboardSnapshot snapshot = new DashboardSnapshot();
        snapshot.setViewName("alerts");
        snapshot.setSnapshotKey("alerts:latest");
        snapshot.setPayloadJson(payloadJson);
        return snapshot;
    }
}
