package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Time-to-live values for the different Redis cache families.
 */
@Data
@ConfigurationProperties(prefix = "app.redis.ttl")
public class RedisCacheProperties {
    /* --- Session and prediction TTLs --- */
    private Duration sessionBuffer;
    private Duration nextActions;
    /* --- Risk and statistics TTLs --- */
    private Duration risk;
    private Duration liveStats;
    /* --- Anomaly and alert TTLs --- */
    private Duration activeAnomaly;
    private Duration pendingAlerts;
    /* --- Insight and dashboard TTLs --- */
    private Duration sessionInsight;
    private Duration dashboard;
    private Duration forecast;
    /* --- Event processing TTLs --- */
    private Duration processedEvent;
}
