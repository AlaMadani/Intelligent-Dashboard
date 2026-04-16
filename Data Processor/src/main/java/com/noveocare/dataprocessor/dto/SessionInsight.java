package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder(toBuilder = true)
public class SessionInsight {
    String insuredId;
    String sessionId;
    Instant computedAt;
    boolean binaryAnomaly;
    boolean anomaly;
    Double anomalyScore;
    Double anomalyProbability;
    String binaryDetectorArtifact;
    String anomalyType;
    Double anomalyTypeConfidence;
    Double churnProbability;
    Integer personaCluster;
    Double ensembleRiskScore;
    String riskLevel;
    PathDeviationResult pathDeviation;
    List<NextActionScore> nextActions;
    List<String> triggeredRules;
    List<String> warnings;
    List<FeatureContribution> topContributingFeatures;
    String explainabilityText;
}
