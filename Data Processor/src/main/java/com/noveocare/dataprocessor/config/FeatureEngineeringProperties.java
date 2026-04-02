package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime knobs used while deriving sequence-model features.
 */
@Data
@ConfigurationProperties(prefix = "app.features")
public class FeatureEngineeringProperties {
    // Upper bound applied to inter-event deltas before normalization.
    private int deltaClipSeconds;
}
