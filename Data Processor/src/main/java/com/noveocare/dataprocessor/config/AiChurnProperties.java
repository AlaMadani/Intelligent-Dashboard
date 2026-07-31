package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AI-driven churn prediction.
 * Controls churn model enablement, model selection, and risk thresholds.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.churn")
public class AiChurnProperties {
    /* --- Feature toggles --- */
    private boolean enabled = true;
    /* --- Model selection --- */
    private String preferredModel = "profile_only_ExtraTrees";
    /* --- Risk thresholds --- */
    private double highThreshold = 0.70;
    private double mediumThreshold = 0.30;
}
