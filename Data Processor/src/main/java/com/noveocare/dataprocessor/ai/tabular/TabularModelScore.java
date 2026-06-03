package com.noveocare.dataprocessor.ai.tabular;

import lombok.Builder;
import lombok.Value;

import java.util.List;

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

    public static TabularModelScore unavailable(String modelName, String artifactName, String warning) {
        return TabularModelScore.builder()
                .modelName(modelName)
                .artifactName(artifactName)
                .available(false)
                .warnings(warning == null ? List.of() : List.of(warning))
                .build();
    }
}
