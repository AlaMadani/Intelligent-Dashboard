package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "app.redis.ttl")
public class RedisCacheProperties {
    private Duration sessionBuffer;
    private Duration aeScore;
    private Duration nextActions;
    private Duration risk;
    private Duration liveStats;
    private Duration activeAnomaly;
    private Duration pendingAlerts;
}
