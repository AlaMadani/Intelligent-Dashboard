package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for tabular anomaly detection models (XGBoost, LightGBM, CatBoost, OneClassSVM).
 * Controls per-model enablement, fail-open behavior, and per-call timeout.
 */
@Data
@ConfigurationProperties(prefix = "app.ai.tabular-anomaly")
public class AiTabularAnomalyProperties {
    /* --- Global toggle --- */
    private boolean enabled = true;
    /* --- Per-model enablement --- */
    private boolean xgboostEnabled = true;
    private boolean lightgbmEnabled = true;
    private boolean catboostEnabled = true;
    private boolean oneclasssvmEnabled = true;
    /* --- Error handling and timeout --- */
    private boolean failOpen = true;
    private long timeoutMs = 150L;
}