package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.load-shedding")
public class AiLoadSheddingProperties {
    private long tcnLagThreshold = 5_000L;
    private long rulesOnlyLagThreshold = 20_000L;
}
