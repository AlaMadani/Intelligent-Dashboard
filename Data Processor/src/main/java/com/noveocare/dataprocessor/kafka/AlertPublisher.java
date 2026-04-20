package com.noveocare.dataprocessor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.KafkaTopicProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RedisPubSubProperties;
import com.noveocare.dataprocessor.config.RiskProperties;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.entity.AnomalyEvent;
import com.noveocare.dataprocessor.mapper.AnomalyAlertMapper;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.repository.AnomalyEventRepository;
import com.noveocare.dataprocessor.service.DashboardSnapshotService;
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
    private final DashboardSnapshotService dashboardSnapshotService;
    private final AnomalyAlertMapper anomalyAlertMapper;
    private final RedisPubSubProperties redisPubSubProperties;
    private final RiskProperties riskProperties;

    public void publish(AnomalyAlert alert, String alertContextJson) {
        // Store the alert first so it is not lost if Kafka delivery fails later.
        persist(alert, alertContextJson);
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
        dashboardSnapshotService.refreshAlertsFeed();
        String pubSubChannel = resolvePubSubChannel(alert);
        if (pubSubChannel != null) {
            redisCacheService.publishJson(pubSubChannel, alert);
        }
    }

    public void persistOnly(AnomalyAlert alert, String alertContextJson) {
        // This path is used when inference or publishing failed but the alert still needs an audit record.
        persist(alert, alertContextJson);
        log.warn("Alert persisted without publish insuredId={} sessionId={} tier={} rule={}",
                alert.getInsuredId(),
                alert.getSessionId(),
                alert.getAnomalyTier(),
                alert.getRuleType());
    }

    private void persist(AnomalyAlert alert, String alertContextJson) {
        // Persist only compact derived context, not raw session event logs.
        AnomalyEvent entity = anomalyAlertMapper.toEntity(alert, alertContextJson);
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

    private String resolvePubSubChannel(AnomalyAlert alert) {
        if (alert == null) {
            return null;
        }
        if ("SYSTEM_TRAFFIC_ANOMALY".equalsIgnoreCase(alert.getAnomalyType())) {
            return redisPubSubProperties.getSystemTrafficAnomalyChannel();
        }
        if (alert.getRiskScore() != null && alert.getRiskScore() >= riskProperties.getCriticalThreshold()) {
            return redisPubSubProperties.getCriticalAlertsChannel();
        }
        return null;
    }
}
