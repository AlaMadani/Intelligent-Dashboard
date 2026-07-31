package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Aggregate forecast-dashboard response for the V36 schema.
 * <p>
 * Provides predicted event volume, anomaly rate, and alert expectations
 * alongside historical comparison data and model metadata.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ForecastDashboardResponse {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** The date to which this forecast applies. */
    private LocalDate forecastDate;
    /** Predicted total number of events for the forecast date. */
    private long predictedTotalEvents;
    /** Predicted anomaly rate (0.0 – 1.0) for the forecast date. */
    private double predictedAnomalyRate;
    /** Expected alert volume for the forecast date. */
    private long expectedAlertVolume;
    /** Historical total events data for comparison (may be a series or summary). */
    private Object historicalTotalEvents;
    /** Historical anomaly rate data for comparison (may be a series or summary). */
    private Object historicalAnomalyRate;
    /** Map of forecast-model names to their artifact identifiers. */
    private Map<String, String> forecastModelNames;
    /** List of warnings generated during forecast computation. */
    private List<String> forecastWarnings;
    /** Source system or component that produced this forecast. */
    private String source;
}
