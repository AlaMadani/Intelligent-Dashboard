package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.risk-fusion")
public class AiRiskFusionProperties {
    private boolean enabled = true;
    private boolean renormalizeMissingModelWeights = true;
    private double xgboostWeight = 0.30;
    private double lightgbmWeight = 0.25;
    private double transformerWeight = 0.20;
    private double tcnWeight = 0.10;
    private double rulesWeight = 0.15;
    private double businessContextWeight = 0.0;
    private double mediumThreshold = 35.0;
    private double highThreshold = 60.0;
    private double criticalThreshold = 80.0;
}
