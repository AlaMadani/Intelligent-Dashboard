package com.noveocare.dataprocessor.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.noveocare.dataprocessor.kafka.AuditTrailConsumer;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Kafka infrastructure configuration: listener container factory, startup logging,
 * and partition-count validation for the audit-trail topic.
 */
@Configuration
@Slf4j
public class KafkaConfig {

    /* --- Dependencies --- */
    private final KafkaProperties kafkaProperties;
    private final KafkaTopicProperties topicProperties;
    private final KafkaConsumerProperties consumerProperties;
    private final AdminClient adminClient;
    private final ObjectFactory<AuditTrailConsumer> auditTrailConsumerFactory;

    public KafkaConfig(KafkaProperties kafkaProperties,
                       KafkaTopicProperties topicProperties,
                       KafkaConsumerProperties consumerProperties,
                       ObjectFactory<AuditTrailConsumer> auditTrailConsumerFactory) {
        this.kafkaProperties = kafkaProperties;
        this.topicProperties = topicProperties;
        this.consumerProperties = consumerProperties;
        this.auditTrailConsumerFactory = auditTrailConsumerFactory;
        Map<String, Object> adminProps = Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        this.adminClient = AdminClient.create(adminProps);
    }

    /* --- Listener container factory --- */
    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, String>> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(consumerProperties.getConcurrency() != null ? consumerProperties.getConcurrency() : 3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setConsumerRebalanceListener(
                auditTrailConsumerFactory.getObject().createRebalanceListener());
        return factory;
    }

    /* --- Startup diagnostics --- */
    @PostConstruct
    public void logEffectiveKafkaSettings() {
        Map<String, Object> consumerProps = kafkaProperties.buildConsumerProperties();
        log.info("=== Effective Kafka Consumer Configuration ===");
        log.info("bootstrap.servers = {}", consumerProps.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        log.info("group.id = {}", consumerProps.get(ConsumerConfig.GROUP_ID_CONFIG));
        log.info("auto.offset.reset = {}", consumerProps.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        log.info("max.poll.records = {}", consumerProps.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
        log.info("max.poll.interval.ms = {}", consumerProps.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
        log.info("session.timeout.ms = {}", consumerProps.get(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG));
        log.info("heartbeat.interval.ms = {}", consumerProps.get(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG));
        log.info("enable.auto.commit = {}", consumerProps.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        log.info("ack-mode = {}", kafkaProperties.getListener().getAckMode());
        log.info("audit-trail topic = {}", topicProperties.getAuditTrail());
        log.info("configured concurrency = {}", consumerProperties.getConcurrency());
        log.info("============================================");

        checkPartitionCount();
        adminClient.close();
    }

    /* --- Partition-count validation --- */
    private void checkPartitionCount() {
        try {
            String topic = topicProperties.getAuditTrail();
            if (topic == null || topic.isBlank()) {
                return;
            }
            DescribeTopicsResult result = adminClient.describeTopics(java.util.List.of(topic));
            Map<String, TopicDescription> descriptions = result.allTopicNames().get();
            TopicDescription description = descriptions.get(topic);
            if (description == null) {
                log.warn("Topic {} not found, cannot check partition count", topic);
                return;
            }
            int partitionCount = description.partitions().size();
            int concurrency = consumerProperties.getConcurrency() != null ? consumerProperties.getConcurrency() : 3;
            if (concurrency > partitionCount) {
                log.warn("Kafka consumer concurrency is {} but topic {} has {} partitions. Only {} consumers can be active.",
                        concurrency, topic, partitionCount, partitionCount);
            } else {
                log.info("Kafka consumer concurrency {} aligns with topic {} partition count {}", concurrency, topic, partitionCount);
            }

            AuditTrailConsumer consumer = auditTrailConsumerFactory.getObject();
            consumer.setTopicPartitionCount(partitionCount);
            Map<String, Object> props = kafkaProperties.buildConsumerProperties();
            Object autoReset = props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
            if (autoReset != null) consumer.setAutoOffsetReset(String.valueOf(autoReset));
            Object maxPollRec = props.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);
            if (maxPollRec instanceof Number n) consumer.setMaxPollRecords(n.intValue());
            Object maxPollInt = props.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
            if (maxPollInt instanceof Number n) consumer.setMaxPollIntervalMs(n.longValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while checking topic partition count", e);
        } catch (ExecutionException e) {
            log.warn("Failed to describe topic for partition count check", e.getCause());
        }
    }
}