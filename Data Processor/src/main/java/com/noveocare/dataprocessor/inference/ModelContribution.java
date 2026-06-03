package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ModelContribution {
    String modelName;
    Double score100;
    double weight;
    double contribution;
    boolean available;
}
