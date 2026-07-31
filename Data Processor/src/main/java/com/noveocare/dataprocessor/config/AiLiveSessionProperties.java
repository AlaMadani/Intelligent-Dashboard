package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits and sizes for live session management in the AI pipeline.
 * Controls event retention, recent event lookback, and sequence window dimensions.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.session")
public class AiLiveSessionProperties {
    /* --- Event retention limits --- */
    private int recentEventsLimit = 50;
    private int maxSessionEventsRetained = 500;
    /* --- Sequence model window --- */
    private int sequenceWindowSize = 10;
}