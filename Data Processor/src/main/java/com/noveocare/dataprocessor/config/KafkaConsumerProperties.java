package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka consumer tuning: concurrency factor and load-shedding lag threshold.
 */
@Data
@ConfigurationProperties(prefix = "app.kafka.consumer")
public class KafkaConsumerProperties {
    /* --- Consumer parallelism --- */
    private Integer concurrency = 3;
    /* --- Load-shedding threshold (milliseconds) --- */
    private Long loadSheddingLagThreshold = 10_000L;
}