package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Base resource paths for AI models, configuration files, and reports.
 * All paths are resolved relative to the classpath base location.
 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiResourceProperties {
    /* --- Runtime metadata --- */
    private String runtimeVersion = "v3.6.1";
    /* --- Base classpath location --- */
    private String basePath = "classpath:/AI/";
    /* --- Subdirectory paths --- */
    private String modelsPath = "models/";
    private String configPath = "config/";
    private String reportsPath = "reports/";
}
