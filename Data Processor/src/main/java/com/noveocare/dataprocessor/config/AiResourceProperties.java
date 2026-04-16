package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiResourceProperties {
    private String basePath = "classpath:/AI/";
    private String manifest = "deployment_manifest.json";
    private String featureBundle = "feature_bundle.json";
}
