package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ForecastDashboardResponse {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private LocalDate forecastDate;
    private long predictedTotalEvents;
    private double predictedAnomalyRate;
    private long expectedAlertVolume;
    private Object historicalTotalEvents;
    private Object historicalAnomalyRate;
    private Map<String, String> forecastModelNames;
    private List<String> forecastWarnings;
}
