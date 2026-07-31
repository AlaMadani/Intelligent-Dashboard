package com.noveocare.dataprocessor.ai.sequence;

import lombok.Builder;
import lombok.Value;

/**
 * Contribution of a single categorical field to the anomaly score, including
 * the NLL and the raw/target values.
 */
@Value
@Builder
public class SequenceFieldContribution {
    String field;
    Double contribution;
    Double nll;
    Integer targetIndex;
    Long encodedId;
    String rawValue;
}
