package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for next-action prediction.
 * Currently disabled as this feature was intentionally skipped in the V3.6.1 refactor.
 */
@Data
@ConfigurationProperties(prefix = "app.next-action-prediction")
public class NextActionPredictionProperties {
    /* --- Feature state --- */
    private boolean enabled = false;
    private String disabledReason = "Intentionally disabled in V3.6.1";
}