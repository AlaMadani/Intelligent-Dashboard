package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neo.dashboard.dto.CommandCenterDto;
import com.neo.dashboard.dto.StatsResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandCenterServiceTest {

    private final StatsService statsService = mock(StatsService.class);
    private final DashboardReadService dashboardReadService = mock(DashboardReadService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private CommandCenterService service;

    @BeforeEach
    void setUp() {
        service = new CommandCenterService(statsService, dashboardReadService, objectMapper);
    }

    @Test
    void getCommandCenterIncludesAllWorkerDashboardViews() {
        LocalDate date = LocalDate.of(2026, 4, 27);
        when(statsService.getLiveStats(date))
                .thenReturn(new StatsResponseDto(date, "redis", objectMapper.createObjectNode().put("kind", "live")));
        when(statsService.getTrendStats(date))
                .thenReturn(new StatsResponseDto(date, "redis", objectMapper.createObjectNode().put("kind", "trend")));
        when(dashboardReadService.getSnapshot("alerts")).thenReturn(Optional.of(itemsNode("alerts")));
        when(dashboardReadService.getSnapshot("risky-sessions")).thenReturn(Optional.of(itemsNode("risky")));
        when(dashboardReadService.getSnapshot("cluster-mix")).thenReturn(Optional.of(itemsNode("cluster")));
        when(dashboardReadService.getSnapshot("drop-offs")).thenReturn(Optional.of(itemsNode("drop")));
        when(dashboardReadService.getSnapshot("path-deviations")).thenReturn(Optional.of(itemsNode("path")));
        when(dashboardReadService.getSnapshot("forecasts")).thenReturn(Optional.of(itemsNode("forecast")));

        CommandCenterDto result = service.getCommandCenter(date);

        assertThat(result.getClusterMix().path("items")).hasSize(1);
        assertThat(result.getDropOffs().path("items")).hasSize(1);
        assertThat(result.getPathDeviations().path("items")).hasSize(1);
        assertThat(result.getForecastDetails().path("items")).hasSize(1);
    }

    private ObjectNode itemsNode(String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("items", objectMapper.createArrayNode().add(value));
        return node;
    }
}
