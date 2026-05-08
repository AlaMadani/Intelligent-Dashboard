package com.noveocare.dataprocessor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.TextNormalization;
import com.noveocare.dataprocessor.ai.FeatureEngineeringService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.KafkaConsumerProperties;
import com.noveocare.dataprocessor.config.KafkaTopicProperties;
import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.NextActionScore;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.entity.NextActionPrediction;
import com.noveocare.dataprocessor.entity.SessionAnalysis;
import com.noveocare.dataprocessor.inference.GeoJumpDetector;
import com.noveocare.dataprocessor.inference.ModelInferenceService;
import com.noveocare.dataprocessor.inference.TransitionMatrixService;
import com.noveocare.dataprocessor.inference.VelocityDetector;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
import com.noveocare.dataprocessor.repository.NextActionPredictionRepository;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import com.noveocare.dataprocessor.service.DashboardSnapshotService;
import com.noveocare.dataprocessor.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditTrailConsumer {

    private final ObjectMapper objectMapper;
    private final RedisSessionBufferService sessionBufferService;
    private final FeatureEngineeringService featureEngineeringService;
    private final ModelInferenceService modelInferenceService;
    private final RuleProperties ruleProperties;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties redisCacheProperties;
    private final FeatureEngineeringProperties featureEngineeringProperties;
    private final StatisticsService statisticsService;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final NextActionPredictionRepository nextActionPredictionRepository;
    private final AlertPublisher alertPublisher;
    private final VelocityDetector velocityDetector;
    private final GeoJumpDetector geoJumpDetector;
    private final TransitionMatrixService transitionMatrixService;
    private final DashboardSnapshotService dashboardSnapshotService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicProperties kafkaTopicProperties;
    private final KafkaConsumerProperties kafkaConsumerProperties;

    @KafkaListener(
            topics = "${app.kafka.topics.audit-trail}",
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${app.kafka.consumer.concurrency:3}")
    public void consume(ConsumerRecord<String, String> record, Consumer<?, ?> consumer) {
        String payload = record.value();
        if (payload == null || payload.isBlank()) {
            return;
        }

        AuditTrailEvent event;
        try {
            event = objectMapper.readValue(payload, AuditTrailEvent.class);
        } catch (Exception ex) {
            log.error("Failed to parse audit event JSON", ex);
            routeToDlq(record, "invalid_json", ex);
            return;
        }

        if (event.getInsuredId() == null || event.getSessionId() == null) {
            log.warn("Skipping event without insuredId/sessionId: {}", payload);
            return;
        }

        try {
            statisticsService.recordEvent(event);
            sessionBufferService.appendEvent(event);

            List<AuditTrailEvent> sessionEvents = sessionBufferService.getSessionEvents(
                    event.getInsuredId(),
                    event.getSessionId());
            List<AuditTrailEvent> enrichedEvents = featureEngineeringService.enrichSessionEvents(sessionEvents);

            SessionSummary summary = featureEngineeringService.buildSessionSummary(enrichedEvents);
            List<String> triggeredRules = evaluateSessionRules(enrichedEvents);

            boolean isEnd = isSessionEnd(event);
            SessionInsight insight;

            if (isEnd) {
                long lag = estimatePartitionLag(record, consumer);
                boolean heavyInferenceEnabled = lag < kafkaConsumerProperties.getLoadSheddingLagThreshold();
                insight = heavyInferenceEnabled
                        ? modelInferenceService.infer(summary, enrichedEvents, triggeredRules)
                        : modelInferenceService.inferLightweight(
                        summary,
                        enrichedEvents,
                        triggeredRules,
                        "heavy_inference_disabled_due_to_kafka_lag_" + lag);

                if (shouldAlert(insight) && !hasDetectedAnomaly(summary.getInsuredId(), summary.getSessionId())) {
                    publishAlert(summary, insight, enrichedEvents);
                }
                persistSessionAnalysis(summary, insight, triggeredRules);
                persistNextActions(summary, insight.getNextActions());
                statisticsService.updateUserRiskProfile(summary.getInsuredId());
                dashboardSnapshotService.refreshAll();
                dashboardSnapshotService.removeSessionInsight(summary.getInsuredId(), summary.getSessionId());
                sessionBufferService.deleteSession(summary.getInsuredId(), summary.getSessionId());
                redisCacheService.deleteKey(CacheKeys.detectedAnomalyKey(summary.getInsuredId(), summary.getSessionId()));
            } else {
                insight = modelInferenceService.inferLightweight(
                        summary,
                        enrichedEvents,
                        triggeredRules,
                        "mid_session_lightweight");

                if (shouldAlert(insight) && !hasDetectedAnomaly(summary.getInsuredId(), summary.getSessionId())) {
                    publishAlert(summary, insight, enrichedEvents);
                }
            }

            dashboardSnapshotService.cacheSessionInsight(summary, insight);
            redisCacheService.setJson(
                    CacheKeys.nextActionsKey(summary.getInsuredId()),
                    insight.getNextActions(),
                    redisCacheProperties.getNextActions());
        } catch (Exception ex) {
            log.error("Processing failed for topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset(), ex);
            routeToDlq(record, "processing_failure", ex);
        }
    }

    private boolean shouldPersist(SessionInsight insight) {
        return insight.isAnomaly()
                || (insight.getEnsembleRiskScore() != null
                && insight.getEnsembleRiskScore() >= featureEngineeringProperties.getSessionAlertRiskThreshold());
    }

    private long estimatePartitionLag(ConsumerRecord<String, String> record, Consumer<?, ?> consumer) {
        try {
            TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
            Long endOffset = consumer.endOffsets(Set.of(topicPartition)).get(topicPartition);
            if (endOffset == null) {
                return 0L;
            }
            return Math.max(0L, endOffset - record.offset() - 1);
        } catch (Exception ex) {
            log.debug("Could not estimate lag for topic={} partition={}", record.topic(), record.partition(), ex);
            return 0L;
        }
    }

    private void routeToDlq(ConsumerRecord<String, String> record, String reason, Exception ex) {
        if (kafkaTopicProperties.getDlq() == null || kafkaTopicProperties.getDlq().isBlank()) {
            return;
        }
        Map<String, Object> dlqPayload = new LinkedHashMap<>();
        dlqPayload.put("reason", reason);
        dlqPayload.put("topic", record.topic());
        dlqPayload.put("partition", record.partition());
        dlqPayload.put("offset", record.offset());
        dlqPayload.put("key", record.key());
        dlqPayload.put("value", record.value());
        dlqPayload.put("error", ex == null ? null : ex.getMessage());
        dlqPayload.put("processedAt", Instant.now().toString());
        try {
            kafkaTemplate.send(kafkaTopicProperties.getDlq(), record.key(), objectMapper.writeValueAsString(dlqPayload));
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("Failed to serialize DLQ payload for topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset(), jsonProcessingException);
        }
    }

    private void persistSessionAnalysis(SessionSummary summary, SessionInsight insight, List<String> triggeredRules) {
        SessionAnalysis entity = new SessionAnalysis();
        entity.setInsuredId(summary.getInsuredId());
        entity.setSessionId(summary.getSessionId());
        entity.setPersona(summary.getPersona());
        entity.setCountryCode(summary.getCountryCode());
        entity.setCity(summary.getCity());
        entity.setMonth(summary.getMonth());
        entity.setSessionNumber(summary.getSessionNumber());
        entity.setStartTime(summary.getSessionStart());
        entity.setEndTime(summary.getSessionEnd());
        entity.setFirstAction(summary.getFirstAction());
        entity.setLastAction(summary.getLastAction());
        entity.setFirstRoute(summary.getFirstRoute());
        entity.setLastRoute(summary.getLastRoute());
        entity.setTotalEvents(summary.getTotalEvents());
        entity.setSessionDurationSeconds(summary.getTotalDurationSeconds());
        entity.setUniqueActions(summary.getUniqueActions());
        entity.setUniqueRoutes(summary.getUniqueRoutes());
        entity.setUniqueIpsUsed(summary.getUniqueIpsUsed());
        entity.setUniqueDevicesUsed(summary.getUniqueDevicesUsed());
        entity.setTotalKOs(summary.getTotalKOs());
        entity.setTotalOKs(summary.getTotalOKs());
        entity.setLongestKoStreak(summary.getLongestKoStreak());
        entity.setKoRate(summary.getTotalEvents() == null || summary.getTotalEvents() == 0
                ? 0.0
                : (double) defaultInt(summary.getTotalKOs()) / summary.getTotalEvents());
        entity.setAvgInterActionSeconds(summary.getAvgInterActionSeconds());
        entity.setMinInterActionSeconds(summary.getMinInterActionSeconds());
        entity.setMaxInterActionSeconds(summary.getMaxInterActionSeconds());
        entity.setActionDiversity(shannonEntropy(summary.getActionCounts(), summary.getTotalEvents()));
        entity.setHasLogin(defaultInt(summary.getHasLogin()) == 1);
        entity.setHasLogout(defaultInt(summary.getHasLogout()) == 1);
        entity.setIpChanged(defaultInt(summary.getIpChanged()) == 1);
        entity.setDeviceChanged(defaultInt(summary.getDeviceChanged()) == 1);
        entity.setTotalDownloadActions(summary.getTotalDownloadActions());
        entity.setMaxDownloadsIn2Minutes(summary.getMaxDownloadsIn2Minutes());
        entity.setPingPongCount(summary.getPingPongCount());
        entity.setRiskScoreMax(summary.getRiskScoreMax());
        entity.setRiskScoreAvg(summary.getRiskScoreAvg());
        entity.setEndedAbruptly(defaultInt(summary.getEndedAbruptly()) == 1);
        entity.setAnomalyEventCount(summary.getAnomalyEventCount());
        entity.setIsoScore(insight.getAnomalyScore());
        entity.setIsAnomaly(insight.isAnomaly());
        entity.setAnomalyType(insight.getAnomalyType());
        entity.setTypeConfidence(insight.getAnomalyTypeConfidence());
        entity.setAnomalyProbability(insight.getAnomalyProbability());
        entity.setChurnProbability(insight.getChurnProbability());
        entity.setEnsembleRiskScore(insight.getEnsembleRiskScore());
        entity.setPersonaCluster(insight.getPersonaCluster());
        entity.setBinaryDetectorArtifact(insight.getBinaryDetectorArtifact());
        entity.setExplainabilityText(insight.getExplainabilityText());
        entity.setPathDeviation(insight.getPathDeviation() != null && insight.getPathDeviation().isDeviated());
        entity.setTransitionProbability(insight.getPathDeviation() == null ? null : insight.getPathDeviation().getTransitionProbability());
        entity.setTransitionFromAction(insight.getPathDeviation() == null ? null : insight.getPathDeviation().getFromAction());
        entity.setTransitionToAction(insight.getPathDeviation() == null ? null : insight.getPathDeviation().getToAction());
        entity.setRuleTriggered(!triggeredRules.isEmpty());
        entity.setRuleType(triggeredRules.isEmpty() ? null : String.join(",", triggeredRules));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        try {
            entity.setActionCountsJson(objectMapper.writeValueAsString(summary.getActionCounts()));
            entity.setAnomalyTypesJson(objectMapper.writeValueAsString(summary.getAnomalyTypes()));
            entity.setCampaignIdsJson(objectMapper.writeValueAsString(summary.getCampaignIds()));
            entity.setActionSequenceJson(objectMapper.writeValueAsString(summary.getActionSequence()));
            entity.setRouteSequenceJson(objectMapper.writeValueAsString(summary.getRouteSequence()));
            entity.setTop3NextActions(objectMapper.writeValueAsString(
                    insight.getNextActions().stream().map(NextActionScore::getAction).toList()));
            entity.setFeatureContributionsJson(objectMapper.writeValueAsString(insight.getTopContributingFeatures()));
            entity.setWarningsJson(objectMapper.writeValueAsString(insight.getWarnings()));
            entity.setTriggeredRulesJson(objectMapper.writeValueAsString(insight.getTriggeredRules()));
            entity.setContextTagsJson(objectMapper.writeValueAsString(insight.getContextTags()));
            entity.setRareTransitionsJson(objectMapper.writeValueAsString(insight.getRareTransitions()));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize JSON fields for session {}", summary.getSessionId(), ex);
        }
        entity.setActionSequenceSignature(summary.getActionSequenceSignature());
        entity.setRouteSequenceSignature(summary.getRouteSequenceSignature());
        sessionAnalysisRepository.save(entity);
        redisCacheService.setJson(
                CacheKeys.sessionAnalysisKey(summary.getInsuredId(), summary.getSessionId()),
                entity,
                redisCacheProperties.getLiveStats());
    }

    private void persistNextActions(SessionSummary summary, List<NextActionScore> nextActions) {
        NextActionPrediction prediction = nextActionPredictionRepository.findByInsuredId(summary.getInsuredId())
                .orElseGet(NextActionPrediction::new);
        prediction.setInsuredId(summary.getInsuredId());
        prediction.setSessionId(summary.getSessionId());
        prediction.setPredictedAt(Instant.now());
        try {
            prediction.setTop3ActionsJson(objectMapper.writeValueAsString(
                    nextActions.stream().map(NextActionScore::getAction).toList()));
        } catch (JsonProcessingException ex) {
            prediction.setTop3ActionsJson(null);
        }
        nextActionPredictionRepository.save(prediction);
    }

    private void publishAlert(SessionSummary summary, SessionInsight insight, List<AuditTrailEvent> enrichedEvents) {
        AuditTrailEvent lastEvent = enrichedEvents.get(enrichedEvents.size() - 1);
        AnomalyAlert alert = AnomalyAlert.builder()
                .insuredId(summary.getInsuredId())
                .sessionId(summary.getSessionId())
                .eventId(lastEvent.getId())
                .eventTime(lastEvent.getCreatedAt())
                .anomalyTier("SESSION_RUNTIME")
                .anomalyType(insight.getAnomalyType())
                .anomalyScore(insight.getAnomalyScore())
                .anomalyProbability(insight.getAnomalyProbability())
                .typeConfidence(insight.getAnomalyTypeConfidence())
                .ruleType(insight.getTriggeredRules().isEmpty() ? null : String.join(",", insight.getTriggeredRules()))
                .anomalyFlag(insight.isAnomaly())
                .churnProbability(insight.getChurnProbability())
                .riskScore(insight.getEnsembleRiskScore())
                .personaCluster(insight.getPersonaCluster())
                .pathDeviation(insight.getPathDeviation() != null && insight.getPathDeviation().isDeviated())
                .transitionProbability(insight.getPathDeviation() == null ? null : insight.getPathDeviation().getTransitionProbability())
                .transitionFromAction(insight.getPathDeviation() == null ? null : insight.getPathDeviation().getFromAction())
                .transitionToAction(insight.getPathDeviation() == null ? null : insight.getPathDeviation().getToAction())
                .modelArtifact(insight.getBinaryDetectorArtifact())
                .nextActions(insight.getNextActions())
                .detectedAt(Instant.now())
                .build();

        try {
            alertPublisher.publish(alert, buildAlertContextJson(summary, insight, lastEvent));
        } catch (JsonProcessingException ex) {
            alertPublisher.publish(alert, "{}");
        }
        redisCacheService.setJson(
                CacheKeys.detectedAnomalyKey(summary.getInsuredId(), summary.getSessionId()),
                Boolean.TRUE,
                redisCacheProperties.getSessionBuffer());
    }

    private boolean shouldAlert(SessionInsight insight) {
        return insight.isAnomaly()
                || (insight.getEnsembleRiskScore() != null
                && insight.getEnsembleRiskScore() >= featureEngineeringProperties.getSessionAlertRiskThreshold());
    }

    private boolean hasDetectedAnomaly(String insuredId, String sessionId) {
        return redisCacheService.hasKey(CacheKeys.detectedAnomalyKey(insuredId, sessionId));
    }

    private boolean isSessionEnd(AuditTrailEvent event) {
        if (event.getAction() == null) {
            return false;
        }
        return ruleProperties.getSessionEndActions().stream()
                .anyMatch(action -> TextNormalization.equalsNormalized(action, event.getAction()));
    }

    private List<String> evaluateSessionRules(List<AuditTrailEvent> sessionEvents) {
        List<AuditTrailEvent> ordered = new ArrayList<>(sessionEvents);
        ordered.sort(Comparator
                .comparing(AuditTrailEvent::getSequenceInSession, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(AuditTrailEvent::getCreatedAt, Comparator.nullsLast(Instant::compareTo)));

        List<String> rules = new ArrayList<>();
        if (ordered.stream().anyMatch(this::isUnusualHour)) {
            rules.add("unusual_hour");
        }
        if (!ordered.isEmpty() && isSkipLogin(ordered.get(0))) {
            rules.add("skip_login");
        }
        if (hasRepeatedFail(ordered)) {
            rules.add("repeated_fail");
        }
        if (velocityDetector.isRapidFire(ordered)) {
            rules.add("rapid_fire");
        }
        if (geoJumpDetector.isGeoJump(ordered)) {
            rules.add("geo_jump");
        }
        if (velocityDetector.isSessionTimeout(ordered)) {
            rules.add("session_timeout");
        }
        if (transitionMatrixService.isImpossibleTransition(ordered)) {
            rules.add("impossible_seq");
        }
        return rules;
    }

    private boolean isUnusualHour(AuditTrailEvent event) {
        if (event.getCreatedAt() == null) {
            return false;
        }
        ZonedDateTime time = ZonedDateTime.ofInstant(event.getCreatedAt(), ZoneOffset.UTC);
        int hour = time.getHour();
        return hour >= ruleProperties.getUnusualHour().getStart()
                && hour <= ruleProperties.getUnusualHour().getEnd();
    }

    private boolean isSkipLogin(AuditTrailEvent firstEvent) {
        if (firstEvent == null || firstEvent.getAction() == null) {
            return false;
        }
        return ruleProperties.getSkipLogin().getAllowedActions().stream()
                .noneMatch(action -> TextNormalization.equalsNormalized(action, firstEvent.getAction()));
    }

    private boolean hasRepeatedFail(List<AuditTrailEvent> ordered) {
        int consecutive = 0;
        for (AuditTrailEvent event : ordered) {
            if ("KO".equalsIgnoreCase(event.getStatus()) && isRepeatedFailType(event.getType())) {
                consecutive++;
                if (consecutive >= ruleProperties.getRepeatedFail().getConsecutive()) {
                    return true;
                }
            } else {
                consecutive = 0;
            }
        }
        return false;
    }

    private boolean isRepeatedFailType(String type) {
        if (type == null) {
            return false;
        }
        return ruleProperties.getRepeatedFail().getTypes().stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(type));
    }

    private String buildAlertContextJson(SessionSummary summary, SessionInsight insight, AuditTrailEvent lastEvent)
            throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", summary.getSessionId());
        payload.put("insuredId", summary.getInsuredId());
        payload.put("lastEventId", lastEvent == null ? null : lastEvent.getId());
        payload.put("lastAction", lastEvent == null ? null : lastEvent.getAction());
        payload.put("lastRoute", lastEvent == null ? null : lastEvent.getRoute());
        payload.put("lastStatus", lastEvent == null ? null : lastEvent.getStatus());
        payload.put("eventTime", lastEvent == null ? null : lastEvent.getCreatedAt());
        Map<String, Object> sessionMetrics = new LinkedHashMap<>();
        sessionMetrics.put("totalEvents", summary.getTotalEvents());
        sessionMetrics.put("totalDurationSeconds", summary.getTotalDurationSeconds());
        sessionMetrics.put("totalKOs", summary.getTotalKOs());
        sessionMetrics.put("maxDownloadsIn2Minutes", summary.getMaxDownloadsIn2Minutes());
        sessionMetrics.put("pingPongCount", summary.getPingPongCount());
        payload.put("sessionMetrics", sessionMetrics);
        payload.put("contextTags", insight.getContextTags());
        payload.put("triggeredRules", insight.getTriggeredRules());
        payload.put("topContributingFeatures", insight.getTopContributingFeatures());
        payload.put("pathDeviation", insight.getPathDeviation());
        payload.put("rareTransitions", insight.getRareTransitions());
        payload.put("explainabilityText", insight.getExplainabilityText());
        return objectMapper.writeValueAsString(payload);
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private double shannonEntropy(Map<String, Long> counts, Integer total) {
        if (counts == null || counts.isEmpty() || total == null || total == 0) {
            return 0.0;
        }
        double entropy = 0.0;
        for (long count : counts.values()) {
            double p = (double) count / total;
            entropy -= p * Math.log(p);
        }
        return entropy;
    }
}
