package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.next-action-prediction")
public class NextActionPredictionProperties {
    private boolean enabled = false;
    private String disabledReason = "Intentionally disabled in V3.6.1";
}