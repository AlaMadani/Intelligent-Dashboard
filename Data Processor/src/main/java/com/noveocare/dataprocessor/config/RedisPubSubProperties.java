package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Pub/Sub channel names used for real-time notifications.
 */
@Data
@ConfigurationProperties(prefix = "app.redis.pubsub")
public class RedisPubSubProperties {
    /* --- Notification channels --- */
    private String criticalAlertsChannel = "CRITICAL_ALERT";
    private String systemTrafficAnomalyChannel = "SYSTEM_TRAFFIC_ANOMALY";
    private String liveStatsChannel = "LIVE_STATS";
}
