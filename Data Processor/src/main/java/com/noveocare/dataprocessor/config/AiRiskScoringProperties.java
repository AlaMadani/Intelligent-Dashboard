package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.risk")
public class AiRiskScoringProperties {
    private double aiScoreScale = 5.0;
    private double mediumThreshold = 35.0;
    private double highThreshold = 60.0;
    private double criticalThreshold = 80.0;
}
