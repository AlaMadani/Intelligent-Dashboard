package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiResourceProperties {
    private String runtimeVersion = "v3.6.1";
    private String basePath = "classpath:/AI/";
    private String modelsPath = "models/";
    private String configPath = "config/";
    private String reportsPath = "reports/";
}
