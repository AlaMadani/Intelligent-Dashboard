package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

/**
 * A single feature's contribution to a model prediction, carrying its name,
 * importance weight, actual observed value, and a human-readable description.
 */
@Value
@Builder
public class FeatureContribution {
    String feature;
    Double importance;
    Object actualValue;
    String description;
}
