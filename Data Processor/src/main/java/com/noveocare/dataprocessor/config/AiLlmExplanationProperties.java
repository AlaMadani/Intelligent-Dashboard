package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.llm-explanation")
public class AiLlmExplanationProperties {
    private boolean enabled = false;
    private boolean generateEvidencePayload = true;
    private String handledBy = "api-service";
    private boolean includeRawEvidence = true;
    private boolean doNotInventEvidence = true;
}
