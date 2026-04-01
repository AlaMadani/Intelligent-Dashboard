package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.features")
public class FeatureEngineeringProperties {
    private int deltaClipSeconds;
}
