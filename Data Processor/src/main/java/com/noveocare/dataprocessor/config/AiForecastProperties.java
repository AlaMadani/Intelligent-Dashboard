package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AI forecast computations (anomaly rate, total events).
 * Controls refresh behavior, persistence targets, and model selection.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.forecast")
public class AiForecastProperties {
    /* --- Feature and refresh toggles --- */
    private boolean enabled = true;
    private boolean runInListener = false;
    private boolean asyncRefreshEnabled = true;
    private long refreshIntervalSeconds = 60;
    private long minRefreshGapSeconds = 30;
    private boolean dirtyOnEvent = true;
    /* --- Persistence targets --- */
    private boolean writeRedisSnapshot = true;
    private boolean writeSqlSnapshot = true;
    /* --- Model selection --- */
    private String anomalyRatePreferredModel = "Ridge";
    private String totalEventsPreferredModel = "XGBoost";
    private boolean fallbackEnabled = true;
}