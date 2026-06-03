package com.noveocare.dataprocessor.ai.churn;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ChurnPrediction {
    Double probability;
    String riskLevel;
    String modelName;
    String modelArtifact;
    boolean available;
    List<String> warnings;
}
