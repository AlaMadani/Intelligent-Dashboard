package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.forecast")
public class AiForecastProperties {
    private boolean enabled = true;
    private String anomalyRatePreferredModel = "Ridge";
    private String totalEventsPreferredModel = "XGBoost";
    private boolean fallbackEnabled = true;
}
