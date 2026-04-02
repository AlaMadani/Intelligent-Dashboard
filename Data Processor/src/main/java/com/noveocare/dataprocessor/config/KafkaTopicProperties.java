package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Maps Kafka topic names used by the processor.
 */
@Data
@ConfigurationProperties(prefix = "app.kafka.topics")
public class KafkaTopicProperties {
    // Input stream consumed from the audit-trail source.
    private String auditTrail;
    // Output topic that receives detected anomaly alerts.
    private String anomalyAlerts;
}
