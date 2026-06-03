package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ForecastContextDto {
    private Double predictedTotalEvents;
    private Double predictedAnomalyRate;
    private Double expectedAlertVolume;
    private Map<String, String> forecastModelNames;
    private List<String> forecastWarnings;
    private Map<String, Object> raw;
}
