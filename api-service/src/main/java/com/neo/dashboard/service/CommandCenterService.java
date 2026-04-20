package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.CommandCenterDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Builds the aggregated payload required by the dashboard command-center page.
 */
@Service
@RequiredArgsConstructor
public class CommandCenterService {

    private final StatsService statsService;
    private final DashboardReadService dashboardReadService;
    private final ObjectMapper objectMapper;

    public CommandCenterDto getCommandCenter(LocalDate date) {
        return new CommandCenterDto(
                statsService.getLiveStats(date),
                statsService.getTrendStats(date),
                dashboardReadService.getSnapshot("alerts").orElseGet(this::emptyItemsPayload),
                dashboardReadService.getSnapshot("risky-sessions").orElseGet(this::emptyItemsPayload),
                dashboardReadService.getSnapshot("forecasts").orElseGet(objectMapper::createObjectNode)
        );
    }

    private JsonNode emptyItemsPayload() {
        return objectMapper.createObjectNode().set("items", objectMapper.createArrayNode());
    }
}
