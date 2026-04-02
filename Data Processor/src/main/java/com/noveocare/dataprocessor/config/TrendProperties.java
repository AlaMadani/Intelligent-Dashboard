package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parameters that control how trend spikes are identified.
 */
@Data
@ConfigurationProperties(prefix = "app.trend")
public class TrendProperties {
    private double spikeSigma;
}
