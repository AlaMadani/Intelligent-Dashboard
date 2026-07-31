package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Weights and thresholds for the risk fusion layer that combines individual
 * model scores (XGBoost, LightGBM, Transformer, TCN, rules, business context)
 * into a unified risk score.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.risk-fusion")
public class AiRiskFusionProperties {
    /* --- Feature toggles --- */
    private boolean enabled = true;
    private boolean renormalizeMissingModelWeights = true;
    /* --- Model weights (must sum to 1.0) --- */
    private double xgboostWeight = 0.30;
    private double lightgbmWeight = 0.25;
    private double transformerWeight = 0.20;
    private double tcnWeight = 0.10;
    private double rulesWeight = 0.15;
    private double businessContextWeight = 0.0;
    /* --- Risk level thresholds --- */
    private double mediumThreshold = 35.0;
    private double highThreshold = 60.0;
    private double criticalThreshold = 80.0;
}
