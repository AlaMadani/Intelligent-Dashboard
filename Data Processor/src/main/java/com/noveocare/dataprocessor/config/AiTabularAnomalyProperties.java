package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.tabular-anomaly")
public class AiTabularAnomalyProperties {
    private boolean enabled = true;
    private boolean xgboostEnabled = true;
    private boolean lightgbmEnabled = true;
    private boolean catboostEnabled = true;
    private boolean oneclasssvmEnabled = true;
    private boolean failOpen = true;
    private long timeoutMs = 150L;
}