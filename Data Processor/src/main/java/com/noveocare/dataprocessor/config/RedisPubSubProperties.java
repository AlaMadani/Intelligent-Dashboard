package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.redis.pubsub")
public class RedisPubSubProperties {
    private String criticalAlertsChannel = "CRITICAL_ALERT";
    private String systemTrafficAnomalyChannel = "SYSTEM_TRAFFIC_ANOMALY";
    private String liveStatsChannel = "LIVE_STATS";
}
