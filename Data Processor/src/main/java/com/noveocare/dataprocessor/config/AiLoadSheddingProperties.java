package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka consumer lag thresholds at which AI model execution is progressively shed.
 * When lag exceeds tcnLagThreshold the TCN model is skipped; beyond rulesOnlyLagThreshold
 * only rule-based detection remains active.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.load-shedding")
public class AiLoadSheddingProperties {
    /* --- Lag thresholds (milliseconds) --- */
    private long tcnLagThreshold = 5_000L;
    private long rulesOnlyLagThreshold = 20_000L;
}
