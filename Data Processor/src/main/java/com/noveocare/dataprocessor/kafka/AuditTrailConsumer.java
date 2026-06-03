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
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.entity.SessionAnalysis;
import com.noveocare.dataprocessor.inference.GeoJumpDetector;
import com.noveocare.dataprocessor.inference.ModelInferenceService;
import com.noveocare.dataprocessor.inference.VelocityDetector;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
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
    private final AlertPublisher alertPublisher;
    private final VelocityDetector velocityDetector;
    private final GeoJumpDetector geoJumpDetector;
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
            long lag = estimatePartitionLag(record, consumer);
            SessionInsight insight = modelInferenceService.infer(summary, enrichedEvents, triggeredRules, lag);

            if (isEnd) {
                if (shouldAlert(insight) && !hasDetectedAnomaly(summary.getInsuredId(), summary.getSessionId())) {
                    publishAlert(summary, insight, enrichedEvents);
                }
                persistSessionAnalysis(summary, insight, triggeredRules);
                statisticsService.updateUserRiskProfile(summary.getInsuredId());
                dashboardSnapshotService.refreshAll();
                dashboardSnapshotService.removeSessionInsight(summary.getInsuredId(), summary.getSessionId());
                sessionBufferService.deleteSession(summary.getInsuredId(), summary.getSessionId());
                redisCacheService.deleteKey(CacheKeys.detectedAnomalyKey(summary.getInsuredId(), summary.getSessionId()));
            } else {
                if (shouldAlert(insight) && !hasDetectedAnomaly(summary.getInsuredId(), summary.getSessionId())) {
                    publishAlert(summary, insight, enrichedEvents);
                }
            }

            dashboardSnapshotService.cacheSessionInsight(summary, insight);
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
        entity.setCountryCode(summary.getCountryCode());
        entity.setStartTime(summary.getSessionStart());
        entity.setEndTime(summary.getSessionEnd());
        entity.setTotalEvents(summary.getTotalEvents());
        entity.setSessionDurationSeconds(summary.getTotalDurationSeconds());
        entity.setUniqueActions(summary.getUniqueActions());
        entity.setChurnProbability(insight.getChurnProbability());
        entity.setPersonaCluster(insight.getPersonaCluster());
        entity.setV36RuntimeVersion("v3.6.1");
        entity.setXgboostAnomalyScore(insight.getXgboostAnomalyScore());
        entity.setXgboostAnomalyScore100(insight.getXgboostAnomalyScore100());
        entity.setLightgbmAlertScore(insight.getLightgbmAlertScore());
        entity.setLightgbmAlertScore100(insight.getLightgbmAlertScore100());
        entity.setCatboostAnomalyScore(insight.getCatboostAnomalyScore());
        entity.setCatboostAnomalyScore100(insight.getCatboostAnomalyScore100());
        entity.setOneClassSvmNoveltyScore(insight.getOneClassSvmNoveltyScoreRaw());
        entity.setOneClassSvmNoveltyScore100(insight.getOneClassSvmNoveltyScore100());
        entity.setSequenceModelArtifact(insight.getSequenceModelArtifact());
        entity.setSelectedSequenceModel(insight.getSelectedSequenceModel());
        entity.setSequenceAnomalyScore(insight.getSequenceAnomalyScore());
        entity.setSequenceCatScore(insight.getSequenceCategoricalScore());
        entity.setSequenceContScore(insight.getSequenceContinuousScore());
        entity.setSequenceCtxScore(insight.getSequenceContextScore());
        entity.setAiRiskScore(insight.getAiRiskScore());
        entity.setRuleRiskScore(insight.getRuleRiskScore());
        entity.setFinalRiskScore(insight.getFinalRiskScore());
        entity.setTransformerSurpriseScore(insight.getTransformerScore());
        entity.setTransformerRiskScore100(insight.getTransformerRiskScore100());
        entity.setTransformerArtifact("transformer_sequence_engine.onnx");
        entity.setFallbackMode(insight.getFallbackMode());
        entity.setTcnSurpriseScore(insight.getTcnScore());
        entity.setTcnRiskScore100(insight.getTcnRiskScore100());
        entity.setTcnArtifact("tcn_sequence_engine.onnx");
        entity.setBusinessContextScore(insight.getBusinessContextScore());
        entity.setAggregationBoost(insight.getAggregationBoost());
        entity.setRiskLevel(insight.getRiskLevel());
        entity.setAnomalyTypeSource(insight.getAnomalyTypeSource());
        entity.setAnomalyTypeConfidence(insight.getAnomalyTypeConfidence());
        entity.setPersonaLabel(insight.getPersonaLabel());
        entity.setPersonaSource(insight.getPersonaSource());
        entity.setPersonaConfidence(insight.getPersonaConfidence());
        entity.setChurnRiskLevel(insight.getChurnRiskLevel());
        entity.setChurnModelName(insight.getChurnModelName());
        entity.setChurnModelArtifact(insight.getChurnModelArtifact());
        entity.setForecastTotalEventsModel(insight.getForecastTotalEventsModel());
        entity.setForecastAnomalyRateModel(insight.getForecastAnomalyRateModel());
        entity.setCreatedAt(Instant.now());

        try {
            entity.setActionCountsJson(objectMapper.writeValueAsString(summary.getActionCounts()));
            entity.setActionSequenceJson(objectMapper.writeValueAsString(summary.getActionSequence()));
            entity.setRouteSequenceJson(objectMapper.writeValueAsString(summary.getRouteSequence()));
            entity.setWarningsJson(objectMapper.writeValueAsString(insight.getWarnings()));
            entity.setTriggeredRulesJson(objectMapper.writeValueAsString(insight.getTriggeredRules()));
            entity.setModelArtifactsJson(objectMapper.writeValueAsString(insight.getModelArtifacts()));
            entity.setTopSequenceSurpriseFieldsJson(objectMapper.writeValueAsString(insight.getSequenceTopContributions()));
            entity.setRuleContributionsJson(objectMapper.writeValueAsString(insight.getRuleContributions()));
            entity.setModelContributionsJson(objectMapper.writeValueAsString(insight.getModelContributions()));
            entity.setAnomalyTypeEvidenceJson(objectMapper.writeValueAsString(insight.getAnomalyTypeEvidence()));
            entity.setChurnFeatureWarningsJson(objectMapper.writeValueAsString(insight.getChurnFeatureWarnings()));
            entity.setForecastContextJson(objectMapper.writeValueAsString(insight.getForecastContext()));
            entity.setLlmExplanationEvidencePayloadJson(objectMapper.writeValueAsString(insight.getLlmExplanationEvidencePayload()));
            entity.setTopContributingFeaturesJson(objectMapper.writeValueAsString(insight.getTopContributingFeatures()));
            entity.setInvestigationPayloadJson(objectMapper.writeValueAsString(insight.getInvestigationPayload()));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize JSON fields for session {}", summary.getSessionId(), ex);
        }
        sessionAnalysisRepository.save(entity);
        redisCacheService.setJson(
                CacheKeys.sessionAnalysisKey(summary.getInsuredId(), summary.getSessionId()),
                entity,
                redisCacheProperties.getLiveStats());
    }

    private void publishAlert(SessionSummary summary, SessionInsight insight, List<AuditTrailEvent> enrichedEvents) {
        AuditTrailEvent lastEvent = enrichedEvents.get(enrichedEvents.size() - 1);
        AnomalyAlert alert = AnomalyAlert.builder()
                .schemaVersion("v3.6.1")
                .recordId(lastEvent.getId())
                .insuredId(summary.getInsuredId())
                .sessionId(summary.getSessionId())
                .eventId(lastEvent.getId())
                .eventTime(lastEvent.getCreatedAt())
                .anomalyTier("SESSION_RUNTIME")
                .anomalyType(insight.getAnomalyType())
                .anomalyTypeConfidence(insight.getAnomalyTypeConfidence())
                .anomalyScore(insight.getAnomalyScore())
                .anomalyProbability(insight.getAnomalyProbability())
                .typeConfidence(insight.getAnomalyTypeConfidence())
                .ruleType(insight.getTriggeredRules().isEmpty() ? null : String.join(",", insight.getTriggeredRules()))
                .anomalyFlag(insight.isAnomaly())
                .churnProbability(insight.getChurnProbability())
                .riskScore(insight.getFinalRiskScore() == null ? insight.getEnsembleRiskScore() : insight.getFinalRiskScore())
                .riskLevel(insight.getRiskLevel())
                .personaCluster(insight.getPersonaCluster())
                .personaLabel(insight.getPersonaLabel())
                .pathDeviation(insight.getPathDeviation() != null && insight.getPathDeviation().isDeviated())
                .transitionProbability(insight.getPathDeviation() == null ? null : insight.getPathDeviation().getTransitionProbability())
                .transitionFromAction(insight.getPathDeviation() == null ? null : insight.getPathDeviation().getFromAction())
                .transitionToAction(insight.getPathDeviation() == null ? null : insight.getPathDeviation().getToAction())
                .modelArtifact(insight.getBinaryDetectorArtifact())
                .aiRiskScore(insight.getAiRiskScore())
                .ruleRiskScore(insight.getRuleRiskScore())
                .finalRiskScore(insight.getFinalRiskScore())
                .anomalyTypeSource(insight.getAnomalyTypeSource())
                .modelScores(insight.getModelScores())
                .modelContributions(insight.getModelContributions())
                .triggeredRules(insight.getTriggeredRules())
                .churn(buildChurnPayload(insight))
                .persona(buildPersonaPayload(insight))
                .llmEvidencePayloadAvailable(insight.getLlmExplanationEvidencePayload() != null)
                .llmEvidencePayloadRedisKey(CacheKeys.alertLlmEvidenceKey(lastEvent.getId()))
                .artifactNames(insight.getModelArtifacts())
                .eventAction(lastEvent.getAction())
                .apiTemplate(lastEvent.getApiTemplate())
                .apiFamily(lastEvent.getApiFamily())
                .controller(lastEvent.getController())
                .page(lastEvent.getPage())
                .country(firstNonBlank(lastEvent.getIpCountry(), lastEvent.getCountryCode()))
                .device(lastEvent.getDevice())
                .browser(lastEvent.getBrowser())
                .os(lastEvent.getOs())
                .httpMethod(lastEvent.getHttpMethod())
                .status(lastEvent.getStatus())
                .nextActions(insight.getNextActions() == null ? List.of() : insight.getNextActions())
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
        cacheV36Alert(alert, insight);
    }

    private Map<String, Object> buildChurnPayload(SessionInsight insight) {
        Map<String, Object> churn = new LinkedHashMap<>();
        churn.put("probability", insight.getChurnProbability());
        churn.put("riskLevel", insight.getChurnRiskLevel());
        churn.put("modelName", insight.getChurnModelName());
        churn.put("artifact", insight.getChurnModelArtifact());
        return churn;
    }

    private Map<String, Object> buildPersonaPayload(SessionInsight insight) {
        Map<String, Object> persona = new LinkedHashMap<>();
        persona.put("enabled", false);
        persona.put("label", insight.getPersonaLabel());
        persona.put("source", insight.getPersonaSource());
        persona.put("confidence", insight.getPersonaConfidence());
        persona.put("warnings", insight.getPersonaWarnings());
        return persona;
    }

    private void cacheV36Alert(AnomalyAlert alert, SessionInsight insight) {
        Map<String, Object> liveAlert = new LinkedHashMap<>();
        liveAlert.put("schemaVersion", "v3.6.1");
        liveAlert.put("eventId", alert.getEventId());
        liveAlert.put("recordId", alert.getRecordId());
        liveAlert.put("insuredId", alert.getInsuredId());
        liveAlert.put("sessionId", alert.getSessionId());
        liveAlert.put("timestamp", alert.getEventTime());
        liveAlert.put("eventAction", alert.getEventAction());
        liveAlert.put("apiTemplate", alert.getApiTemplate());
        liveAlert.put("apiFamily", alert.getApiFamily());
        liveAlert.put("controller", alert.getController());
        liveAlert.put("page", alert.getPage());
        liveAlert.put("country", alert.getCountry());
        liveAlert.put("device", alert.getDevice());
        liveAlert.put("browser", alert.getBrowser());
        liveAlert.put("os", alert.getOs());
        liveAlert.put("httpMethod", alert.getHttpMethod());
        liveAlert.put("status", alert.getStatus());
        liveAlert.put("riskLevel", alert.getRiskLevel());
        liveAlert.put("finalRiskScore", alert.getFinalRiskScore());
liveAlert.put("xgboostAnomalyScore", insight.getXgboostAnomalyScore());
        liveAlert.put("xgboostAnomalyScore100", insight.getXgboostAnomalyScore100());
        liveAlert.put("lightgbmAlertScore", insight.getLightgbmAlertScore());
        liveAlert.put("lightgbmAlertScore100", insight.getLightgbmAlertScore100());
        liveAlert.put("transformerRiskScore100", insight.getTransformerRiskScore100());
        liveAlert.put("tcnRiskScore100", insight.getTcnRiskScore100());
        liveAlert.put("ruleRiskScore", insight.getRuleRiskScore());
        liveAlert.put("modelScores", insight.getModelScores());
        liveAlert.put("modelContributions", insight.getModelContributions());
        liveAlert.put("triggeredRuleCodes", alert.getTriggeredRules());
        liveAlert.put("anomalyType", alert.getAnomalyType());
        liveAlert.put("anomalyTypeConfidence", alert.getAnomalyTypeConfidence());
        liveAlert.put("churnProbability", insight.getChurnProbability());
        liveAlert.put("churnRiskLevel", insight.getChurnRiskLevel());
        liveAlert.put("llmEvidencePayloadAvailable", alert.getLlmEvidencePayloadAvailable());
        liveAlert.put("llmEvidencePayloadRedisKey", alert.getLlmEvidencePayloadRedisKey());
        liveAlert.put("alertStatus", "OPEN");
        liveAlert.put("createdAt", alert.getDetectedAt());

        redisCacheService.addToJsonList(CacheKeys.liveAlertsV36Key(), liveAlert, redisCacheProperties.getLiveStats());
        redisCacheService.addToJsonList(CacheKeys.userAlertsKey(alert.getInsuredId()), liveAlert, redisCacheProperties.getLiveStats());
        if ("CRITICAL".equalsIgnoreCase(alert.getRiskLevel())) {
            redisCacheService.addToJsonList(CacheKeys.criticalAlertsV36Key(), liveAlert, redisCacheProperties.getLiveStats());
        }
        if (insight.getLlmExplanationEvidencePayload() != null) {
            redisCacheService.setJson(CacheKeys.alertLlmEvidenceKey(alert.getEventId()),
                    insight.getLlmExplanationEvidencePayload(),
                    redisCacheProperties.getSessionInsight());
        }
        if (insight.getInvestigationPayload() != null) {
            redisCacheService.setJson(CacheKeys.alertInvestigationKey(alert.getEventId()),
                    insight.getInvestigationPayload(),
                    redisCacheProperties.getSessionInsight());
        }
    }

    private boolean shouldAlert(SessionInsight insight) {
        return insight.isAnomaly()
                || (insight.getFinalRiskScore() != null
                && insight.getFinalRiskScore() >= featureEngineeringProperties.getSessionAlertRiskThreshold())
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
        if (ordered.stream().anyMatch(event -> defaultInt(event.getIsDeviceChanged()) == 1)) {
            rules.add("device_switch");
        }
        if (ordered.stream().anyMatch(event -> defaultInt(event.getDownloadsLast2Minutes()) >= 10)) {
            rules.add("download_spike");
        }
        if (ordered.stream().anyMatch(event -> defaultInt(event.getPingPongCount()) >= 2)) {
            rules.add("api_scraping");
        }
        if (velocityDetector.isSessionTimeout(ordered)) {
            rules.add("session_timeout");
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

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String buildAlertContextJson(SessionSummary summary, SessionInsight insight, AuditTrailEvent lastEvent)
            throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
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
        payload.put("sequenceTopContributions", insight.getSequenceTopContributions());
        payload.put("aiRiskScore", insight.getAiRiskScore());
        payload.put("ruleRiskScore", insight.getRuleRiskScore());
        payload.put("finalRiskScore", insight.getFinalRiskScore());
        payload.put("personaLabel", insight.getPersonaLabel());
        payload.put("personaSource", insight.getPersonaSource());
        payload.put("pathDeviation", insight.getPathDeviation());
        payload.put("rareTransitions", insight.getRareTransitions());
        payload.put("explainabilityText", insight.getExplainabilityText());
        return objectMapper.writeValueAsString(payload);
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
