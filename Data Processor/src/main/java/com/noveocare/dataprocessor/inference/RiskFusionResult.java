package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Carries the final fused risk score, per-model contributions, the weights that
 * were actually used, and metadata about fallback mode and warnings.
 */
@Value
@Builder(toBuilder = true)
public class RiskFusionResult {

    /* ---- Fused score fields ---- */
    double finalRiskScore;
    String riskLevel;
    String riskScale;

    /* ---- Per-model contribution fields ---- */
    double xgboostContribution;
    double lightgbmContribution;
    double transformerContribution;
    double tcnContribution;
    double ruleContribution;
    double businessContextContribution;
    double aggregationBoost;

    /* ---- Weight and fallback metadata ---- */
    Map<String, Double> usedWeights;
    Map<String, Double> unavailableModelWeights;
    String fallbackMode;
    List<String> fusionWarnings;
    List<ModelContribution> modelContributions;
}
