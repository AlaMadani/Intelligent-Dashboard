package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder(toBuilder = true)
public class RiskFusionResult {
    double finalRiskScore;
    String riskLevel;
    double xgboostContribution;
    double lightgbmContribution;
    double transformerContribution;
    double tcnContribution;
    double ruleContribution;
    double businessContextContribution;
    double aggregationBoost;
    Map<String, Double> usedWeights;
    Map<String, Double> unavailableModelWeights;
    String fallbackMode;
    List<String> fusionWarnings;
    List<ModelContribution> modelContributions;
}
