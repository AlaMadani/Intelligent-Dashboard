package com.noveocare.dataprocessor.ai.tabular;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Score from a single tabular anomaly model: model name, artifact, score
 * values, latency, and availability status.
 */
@Value
@Builder(toBuilder = true)
public class TabularModelScore {
    String modelName;
    String artifactName;
    boolean available;
    Double score;
    Double score100;
    Double rawScore;
    long latencyMs;
    List<String> warnings;

    /* Returns an unavailable score with a warning. */
    public static TabularModelScore unavailable(String modelName, String artifactName, String warning) {
        return TabularModelScore.builder()
                .modelName(modelName)
                .artifactName(artifactName)
                .available(false)
                .warnings(warning == null ? List.of() : List.of(warning))
                .build();
    }
}
