package com.noveocare.dataprocessor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.KafkaTopicProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.entity.AnomalyEvent;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.AnomalyEventRepository;
import com.noveocare.dataprocessor.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Persists, caches, and publishes anomaly alerts once a tier decides that a
 * session or event should be surfaced.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;
    private final ObjectMapper objectMapper;
    private final AnomalyEventRepository anomalyEventRepository;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;
    private final StatisticsService statisticsService;

    public void publish(AnomalyAlert alert, String rawEventJson) {
        // Store the alert first so it is not lost if Kafka delivery fails later.
        persist(alert, rawEventJson);
        // Keep the latest active anomaly in Redis for fast API/dashboard access.
        cache(alert);
        // Fan the alert out to the anomaly topic for downstream consumers.
        send(alert);
        log.info("Alert published insuredId={} sessionId={} tier={} type={} rule={}",
                alert.getInsuredId(),
                alert.getSessionId(),
                alert.getAnomalyTier(),
                alert.getAnomalyType(),
                alert.getRuleType());
        // Update the near-real-time metrics and user risk snapshot after publication.
        statisticsService.recordAnomalyAlert(alert.getDetectedAt() == null ? Instant.now() : alert.getDetectedAt());
        statisticsService.updateUserRiskProfile(alert.getInsuredId());
    }

    public void persistOnly(AnomalyAlert alert, String rawEventJson) {
        // This path is used when inference or publishing failed but the alert still needs an audit record.
        persist(alert, rawEventJson);
        log.warn("Alert persisted without publish insuredId={} sessionId={} tier={} rule={}",
                alert.getInsuredId(),
                alert.getSessionId(),
                alert.getAnomalyTier(),
                alert.getRuleType());
    }

    private void persist(AnomalyAlert alert, String rawEventJson) {
        // Copy the DTO into the JPA entity so the full alert history is queryable from SQL.
        AnomalyEvent entity = new AnomalyEvent();
        entity.setInsuredId(alert.getInsuredId());
        entity.setSessionId(alert.getSessionId());
        entity.setEventId(alert.getEventId());
        entity.setEventTime(alert.getEventTime());
        entity.setAnomalyTier(alert.getAnomalyTier());
        entity.setAnomalyType(alert.getAnomalyType());
        entity.setAnomalyScore(alert.getAnomalyScore());
        entity.setTypeConfidence(alert.getTypeConfidence());
        entity.setRuleType(alert.getRuleType());
        entity.setEventJson(rawEventJson);
        entity.setDetectedAt(alert.getDetectedAt() == null ? Instant.now() : alert.getDetectedAt());
        anomalyEventRepository.save(entity);
    }

    private void cache(AnomalyAlert alert) {
        // Cache only the latest alert per insured user; historic events stay in SQL.
        redisCacheService.setJson(CacheKeys.activeAnomalyKey(alert.getInsuredId()), alert,
                cacheProperties.getActiveAnomaly());
    }

    private void send(AnomalyAlert alert) {
        try {
            // Serialize on demand so the persisted entity remains the source of truth.
            String payload = objectMapper.writeValueAsString(alert);
            kafkaTemplate.send(topicProperties.getAnomalyAlerts(), alert.getInsuredId(), payload)
                    .whenComplete((SendResult<String, String> result, Throwable ex) -> {
                        // Log the broker outcome but do not retry inline from the Kafka callback.
                        if (ex != null) {
                            log.error("Alert Kafka send failed insuredId={} sessionId={}",
                                    alert.getInsuredId(), alert.getSessionId(), ex);
                            return;
                        }
                        if (result != null && result.getRecordMetadata() != null) {
                            log.info("Alert pushed to Kafka topic={} partition={} offset={}",
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        } else {
                            log.info("Alert pushed to Kafka topic={} (no metadata)",
                                    topicProperties.getAnomalyAlerts());
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize anomaly alert for Kafka", e);
        }
    }
}
