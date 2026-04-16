package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime knobs used while deriving session-centric tabular features.
 */
@Data
@ConfigurationProperties(prefix = "app.features")
public class FeatureEngineeringProperties {
    private int downloadWindowSeconds = 120;
    private int rapidActionSeconds = 7;
    private double sessionAlertRiskThreshold = 60.0;
}
