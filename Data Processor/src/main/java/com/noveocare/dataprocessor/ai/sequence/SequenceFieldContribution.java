package com.noveocare.dataprocessor.ai.sequence;

import lombok.Builder;
import lombok.Value;

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
