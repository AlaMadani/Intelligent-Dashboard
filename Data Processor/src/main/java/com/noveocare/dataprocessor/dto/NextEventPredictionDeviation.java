package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Captures the deviation between a predicted next event and what actually occurred.
 */
@Value
@Builder
public class NextEventPredictionDeviation {
    String sessionId;
    String contextEventId;
    String actualEventId;
    Map<String, String> actual;
    Map<String, Boolean> predictionMatch;
    Map<String, Double> actualProbabilities;
    double deviationScore;
}
