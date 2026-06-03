package com.noveocare.dataprocessor.ai.sequence;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder(toBuilder = true)
public class SequenceScoreResult {
    boolean available;
    String modelKind;
    String modelArtifact;
    Double sequenceAnomalyScore;
    Double categoricalScore;
    Double continuousScore;
    Double contextScore;
    Double aiRiskScore;
    Long latencyMillis;
    List<SequenceFieldContribution> perFieldContributions;
    List<SequenceFieldContribution> topContributingFields;
    List<String> warnings;

    public static SequenceScoreResult unavailable(List<String> warnings) {
        return SequenceScoreResult.builder()
                .available(false)
                .warnings(warnings == null ? List.of() : warnings)
                .perFieldContributions(List.of())
                .topContributingFields(List.of())
                .build();
    }
}
