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
    private Duration sessionBuffer;
    private Duration nextActions;
    private Duration risk;
    private Duration liveStats;
    private Duration activeAnomaly;
    private Duration pendingAlerts;
    private Duration sessionInsight;
    private Duration dashboard;
    private Duration forecast;
    private Duration processedEvent;
}
