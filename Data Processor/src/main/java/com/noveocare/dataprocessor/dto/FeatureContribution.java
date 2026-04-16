package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class FeatureContribution {
    String feature;
    Double importance;
    Object actualValue;
    String description;
}
