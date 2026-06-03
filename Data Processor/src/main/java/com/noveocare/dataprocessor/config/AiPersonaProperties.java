package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.persona")
public class AiPersonaProperties {
    private boolean enabled = false;
    private String skippedReason = "Persona intentionally skipped in V3.6.1 dataprocessor refactor";
}
