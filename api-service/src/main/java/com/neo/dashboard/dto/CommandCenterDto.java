package com.neo.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated payload used by the dashboard home / command-center screen.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommandCenterDto {

    private StatsResponseDto liveStats;
    private StatsResponseDto trendForecast;
    private JsonNode alertFeed;
    private JsonNode riskySessions;
    private JsonNode forecastDetails;
}
