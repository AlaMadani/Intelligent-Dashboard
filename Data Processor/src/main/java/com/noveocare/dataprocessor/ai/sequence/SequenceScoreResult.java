package com.noveocare.dataprocessor.ai.sequence;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Score result from sequence anomaly detection: per-field contributions,
 * aggregated scores, and the final 0-100 AI risk score.
 */
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

    /* Returns an unavailable result with the given warnings. */
    public static SequenceScoreResult unavailable(List<String> warnings) {
        return SequenceScoreResult.builder()
                .available(false)
                .warnings(warnings == null ? List.of() : warnings)
                .perFieldContributions(List.of())
                .topContributingFields(List.of())
                .build();
    }
}
