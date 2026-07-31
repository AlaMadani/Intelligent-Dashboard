package com.noveocare.dataprocessor.ai.churn;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Immutable result of a churn prediction: probability, risk level, model info,
 * and availability status.
 */
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
