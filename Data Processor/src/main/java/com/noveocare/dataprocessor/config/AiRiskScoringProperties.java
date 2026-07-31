package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scaling factor and risk tier thresholds applied to raw AI model scores
 * before they are consumed by the risk fusion layer.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.risk")
public class AiRiskScoringProperties {
    /* --- Score scaling --- */
    private double aiScoreScale = 5.0;
    /* --- Risk level thresholds --- */
    private double mediumThreshold = 35.0;
    private double highThreshold = 60.0;
    private double criticalThreshold = 80.0;
}
