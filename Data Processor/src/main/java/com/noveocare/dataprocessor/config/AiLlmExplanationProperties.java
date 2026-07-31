package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for LLM-generated natural language explanations of alerts.
 * Controls whether evidence payloads are produced, how they are routed, and data inclusion rules.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.llm-explanation")
public class AiLlmExplanationProperties {
    /* --- Feature toggle --- */
    private boolean enabled = false;
    /* --- Evidence payload settings --- */
    private boolean generateEvidencePayload = true;
    private String handledBy = "api-service";
    private boolean includeRawEvidence = true;
    private boolean doNotInventEvidence = true;
}
