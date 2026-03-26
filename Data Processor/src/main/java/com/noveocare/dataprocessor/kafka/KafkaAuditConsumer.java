package com.noveocare.dataprocessor.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.entity.SecurityAlert;
import com.noveocare.dataprocessor.repository.SecurityAlertRepository;
import com.noveocare.dataprocessor.service.AlertSequenceService;
import com.noveocare.dataprocessor.service.AnomalyDetectionService;
import com.noveocare.dataprocessor.service.SlidingWindowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaAuditConsumer {

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "pwd", "token", "access_token", "refresh_token",
            "authorization", "secret", "api_key", "apikey"
    );

    private final SlidingWindowService slidingWindowService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final SecurityAlertRepository securityAlertRepository;
    private final AlertSequenceService alertSequenceService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "topic-audit-trail", groupId = "data-processor-group")
    public void consumeAuditLog(ConsumerRecord<String, String> record) {
        String message = record.value();

        // Validate payload early.
        if (message == null || message.isBlank()) {
            log.warn("Kafka message value is empty; skipping.");
            return;
        }

        try {
            // Parse the JSON event.
            AuditTrailEvent event = objectMapper.readValue(message, AuditTrailEvent.class);

            if (event.getUserKey() != null) {
                // Log payload details only when sensitive fields are absent.
                if (containsSensitiveFieldName(message)) {
                    log.info("Consumed audit event for userKey={} (payload suppressed: sensitive fields detected).", event.getUserKey());
                } else {
                    log.info("Consumed audit event for userKey={} payload={}", event.getUserKey(), message);
                }

                // 1) Append the event to the user's sliding window history in Redis.
                slidingWindowService.addEventToUserHistory(event);

                // 2) Fetch the most recent events for this user.
                List<AuditTrailEvent> recentEvents = slidingWindowService.getRecentEvents(String.valueOf(event.getUserKey()), 5);

                // 3) Run anomaly detection only when the window is full.
                if (recentEvents.size() == 5) {
                    try {
                        log.debug("Triggering anomaly detection for userKey={}", event.getUserKey());

                        // Analyze the full event sequence.
                        AnomalyDetectionService.AnomalyResult result = anomalyDetectionService.analyzeSequence(recentEvents);

                        if (result.isAnomaly()) {
                            log.warn("🚨 Anomaly detected for userKey={} | MSE Score: {}", event.getUserKey(), result.getMseScore());

                            // Build and persist the alert.
                            SecurityAlert alert = new SecurityAlert();
                            alert.setUserKey(event.getUserKey());
                            alert.setIpAddress(event.getIpAddress());
                            alert.setAlertType("LSTM_ANOMALY");

                            alert.setAnomalyScore(result.getMseScore());
                            alert.setThresholdUsed(result.getThresholdUsed());

                            alert.setAiExplanation("Séquence suspecte détectée avec un score MSE de " + result.getMseScore() + ". En attente de l'analyse LLM Gemini.");
                            alert.setDetectedAt(Instant.now());

                            SecurityAlert savedAlert = securityAlertRepository.save(alert);
                            // Persist the alert sequence and prediction metadata.
                            alertSequenceService.persistAlertSequence(savedAlert, recentEvents);
                        }
                    } catch (Exception mlException) {
                        log.error("ML Anomaly detection failed for userKey={}", event.getUserKey(), mlException);
                    }
                } else {
                    log.debug("Not enough events for userKey={} (Current: {}/5). Skipping ML inference.", event.getUserKey(), recentEvents.size());
                }

            } else {
                log.warn("AuditTrailEvent user_key is null; skipping Redis push and ML detection.");
            }

            // Final payload logging with sensitivity check.
            if (containsSensitiveFieldName(message)) {
                log.warn("Payload contains potential sensitive fields; raw payload logging is suppressed.");
            } else {
                log.debug("Kafka raw payload: {}", message);
            }
        } catch (Exception e) {
            log.error("JSON parsing error from Kafka. payload={}", message, e);
        }
    }

    private static boolean containsSensitiveFieldName(String payload) {
        String lower = payload.toLowerCase();
        for (String field : SENSITIVE_FIELDS) {
            if (lower.contains("\"" + field + "\"")) {
                return true;
            }
        }
        return false;
    }
}
