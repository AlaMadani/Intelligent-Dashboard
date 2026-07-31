package com.noveocare.dataprocessor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.KafkaTopicProperties;
import com.noveocare.dataprocessor.config.PerformanceProperties;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service responsible for the full alert lifecycle: persistence, caching,
 * Kafka publishing, stats recording, and Redis pub-sub notifications.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertPublisher {

    /* -- Dependencies -- */
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
    private final PerformanceProperties performanceProperties;

    /* Tracks how many duplicate writes have been skipped since start-up. */
    private final AtomicLong duplicateSqlWritesSkipped = new AtomicLong();

    /* -- Public API -- */

    /**
     * Persist the alert, update the cache, push to Kafka, record statistics,
     * refresh the dashboard, and optionally publish to a Redis pub-sub channel.
     */
    public void publish(AnomalyAlert alert, String alertContextJson) {
        long tStart = System.nanoTime();
        persist(alert, alertContextJson);
        cache(alert);
        send(alert);
        log.info("Alert published insuredId={} sessionId={} tier={} type={} rule={}",
                alert.getInsuredId(),
                alert.getSessionId(),
                alert.getAnomalyTier(),
                alert.getAnomalyType(),
                alert.getRuleType());
        statisticsService.recordAnomalyAlert(alert.getDetectedAt() == null ? Instant.now() : alert.getDetectedAt());
        statisticsService.updateUserRiskProfile(alert.getInsuredId());
        if (!performanceProperties.getHotPath().isSkipDashboardRefreshInListener()) {
            dashboardSnapshotService.refreshAlertsFeed();
        } else {
            dashboardSnapshotService.markAlertsDirty();
            dashboardSnapshotService.markOverviewDirty();
        }
        String pubSubChannel = resolvePubSubChannel(alert);
        if (pubSubChannel != null) {
            redisCacheService.publishJson(pubSubChannel, alert);
        }
        long totalMs = (System.nanoTime() - tStart) / 1_000_000L;
        log.info("ALERT_PUBLISH_TIMING insuredId={} sessionId={} alertType={} rule={} tier={}"
                        + " kafkaSendMs={} status=OK",
                alert.getInsuredId(), alert.getSessionId(),
                alert.getAnomalyType(), alert.getRuleType(), alert.getAnomalyTier(),
                totalMs);
    }

    /**
     * Persist the alert to the database without publishing to Kafka or
     * running the full post-processing pipeline.
     */
    public void persistOnly(AnomalyAlert alert, String alertContextJson) {
        persist(alert, alertContextJson);
        log.warn("Alert persisted without publish insuredId={} sessionId={} tier={} rule={}",
                alert.getInsuredId(),
                alert.getSessionId(),
                alert.getAnomalyTier(),
                alert.getRuleType());
    }

    /* -- Internal helpers -- */

    /* Persist the alert to the anomaly_events table, skipping duplicates. */
    private void persist(AnomalyAlert alert, String alertContextJson) {
        String eventId = alert.getEventId();
        String schemaVersion = alert.getSchemaVersion();
        if (eventId != null && schemaVersion != null) {
            long existsStart = System.currentTimeMillis();
            boolean exists = anomalyEventRepository.existsByEventIdAndV36RuntimeVersion(eventId, schemaVersion);
            long existsMs = System.currentTimeMillis() - existsStart;
            if (existsMs > 1000) {
                log.warn("SQL slow: anomaly_events exists check took {}ms for eventId={}", existsMs, eventId);
            }
            if (exists) {
                duplicateSqlWritesSkipped.incrementAndGet();
                log.debug("Duplicate anomaly_events row skipped for eventId={} schemaVersion={}", eventId, schemaVersion);
                return;
            }
        }
        long saveStart = System.currentTimeMillis();
        AnomalyEvent entity = anomalyAlertMapper.toEntity(alert, alertContextJson);
        anomalyEventRepository.save(entity);
        long saveMs = System.currentTimeMillis() - saveStart;
        if (saveMs > 1000) {
            log.warn("SQL slow: anomaly_events insert took {}ms for eventId={}", saveMs, eventId);
        }
    }

    /* Write the alert into the Redis active-anomaly cache. */
    private void cache(AnomalyAlert alert) {
        redisCacheService.setJson(CacheKeys.activeAnomalyKey(alert.getInsuredId()), alert,
                cacheProperties.getActiveAnomaly());
    }

    /* Serialise and send the alert to the anomaly-alerts Kafka topic. */
    private void send(AnomalyAlert alert) {
        try {
            String payload = objectMapper.writeValueAsString(alert);
            kafkaTemplate.send(topicProperties.getAnomalyAlerts(), alert.getInsuredId(), payload)
                    .whenComplete((SendResult<String, String> result, Throwable ex) -> {
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

    /* Determine which Redis pub-sub channel (if any) should receive this alert. */
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

    /* -- Metrics -- */

    public long getDuplicateSqlWritesSkipped() {
        return duplicateSqlWritesSkipped.get();
    }
}