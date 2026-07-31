package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Placeholder configuration for the persona inference feature.
 * Currently disabled as persona scoring was intentionally skipped during the V3.6.1 refactor.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.persona")
public class AiPersonaProperties {
    /* --- Feature state --- */
    private boolean enabled = false;
    private String skippedReason = "Persona intentionally skipped in V3.6.1 dataprocessor refactor";
}
