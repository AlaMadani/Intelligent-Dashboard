package com.noveocare.dataprocessor.ai.forecast;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Immutable result of a forecast: predicted total events, anomaly rate,
 * expected alert volume, and the feature maps used for each model.
 */
@Value
@Builder
public class ForecastPrediction {
    LocalDate referenceDate;
    LocalDate forecastDate;
    Double totalEventsForecast;
    Double anomalyRateForecast;
    Double expectedAlertVolume;
    String totalEventsModelArtifact;
    String totalEventsModelName;
    String anomalyRateModelArtifact;
    String anomalyRateModelName;
    String anomalyRateStrategy;
    Map<String, Double> totalEventsFeatures;
    Map<String, Double> anomalyRateFeatures;
    List<String> warnings;
}
