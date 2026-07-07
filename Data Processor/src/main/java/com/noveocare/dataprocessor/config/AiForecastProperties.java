package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ai.forecast")
public class AiForecastProperties {
    private boolean enabled = true;
    private boolean runInListener = false;
    private boolean asyncRefreshEnabled = true;
    private long refreshIntervalSeconds = 60;
    private long minRefreshGapSeconds = 30;
    private boolean dirtyOnEvent = true;
    private boolean writeRedisSnapshot = true;
    private boolean writeSqlSnapshot = true;
    private String anomalyRatePreferredModel = "Ridge";
    private String totalEventsPreferredModel = "XGBoost";
    private boolean fallbackEnabled = true;
}