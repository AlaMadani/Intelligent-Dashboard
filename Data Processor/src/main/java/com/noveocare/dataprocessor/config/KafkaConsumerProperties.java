package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.kafka.consumer")
public class KafkaConsumerProperties {
    private Integer concurrency = 3;
    private Long loadSheddingLagThreshold = 10_000L;
}