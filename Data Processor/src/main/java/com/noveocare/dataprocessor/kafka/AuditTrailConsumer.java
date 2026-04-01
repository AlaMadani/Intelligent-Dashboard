package com.noveocare.dataprocessor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.AnomalyThresholdLoader;
import com.noveocare.dataprocessor.ai.FeatureEngineeringService;
import com.noveocare.dataprocessor.ai.VocabService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.dto.AnomalyTypeResult;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.PendingAlert;
import com.noveocare.dataprocessor.dto.SessionStats;
import com.noveocare.dataprocessor.entity.NextActionPrediction;
import com.noveocare.dataprocessor.entity.SessionAnalysis;
import com.noveocare.dataprocessor.inference.ModelInferenceService;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
import com.noveocare.dataprocessor.repository.NextActionPredictionRepository;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import com.noveocare.dataprocessor.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditTrailConsumer {

    private final ObjectMapper objectMapper;
    private final RedisSessionBufferService sessionBufferService;
    private final FeatureEngineeringService featureEngineeringService;
    private final ModelInferenceService modelInferenceService;
    private final AnomalyThresholdLoader anomalyThresholdLoader;
    private final RuleProperties ruleProperties;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties redisCacheProperties;
    private final StatisticsService statisticsService;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final NextActionPredictionRepository nextActionPredictionRepository;
    private final AlertPublisher alertPublisher;
    private final VocabService vocabService;

    private Set<Integer> skipLoginAllowedIds = new HashSet<>();
    private Set<Integer> sessionEndActionIds = new HashSet<>();

    @PostConstruct
    public void init() {
        for (String action : ruleProperties.getSkipLogin().getAllowedActions()) {
            skipLoginAllowedIds.add(vocabService.actionId(action));
        }
        for (String action : ruleProperties.getSessionEndActions()) {
            sessionEndActionIds.add(vocabService.actionId(action));
        }
        log.info("Initialized rule action sets (skipLoginAllowed={}, sessionEnd={})",
                skipLoginAllowedIds.size(), sessionEndActionIds.size());
    }

    @KafkaListener(topics = "${app.kafka.topics.audit-trail}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record) {
        log.info("Kafka record topic={} partition={} offset={} key={}",
                record.topic(), record.partition(), record.offset(), record.key());
        String payload = record.value();
        if (payload == null || payload.isBlank()) {
            log.warn("Kafka message value is empty; skipping.");
            return;
        }

        AuditTrailEvent event;
        try {
            event = objectMapper.readValue(payload, AuditTrailEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse audit event JSON", e);
            return;
        }

        if (event.getInsuredId() == null || event.getSessionId() == null) {
            log.warn("AuditTrailEvent missing insuredId or sessionId; skipping.");
            return;
        }

        log.info("Consumed event insuredId={} sessionId={} action={} seq={} status={} at={}",
                event.getInsuredId(),
                event.getSessionId(),
                event.getAction(),
                event.getSequenceInSession(),
                event.getStatus(),
                event.getCreatedAt());

        sessionBufferService.appendEvent(event);
        statisticsService.recordEvent(event);

        List<AuditTrailEvent> sessionEvents = sessionBufferService
                .getSessionEvents(event.getInsuredId(), event.getSessionId());
        log.info("Session buffer size insuredId={} sessionId={} size={}",
                event.getInsuredId(), event.getSessionId(), sessionEvents.size());

        List<String> tier1Rules = evaluateTier1(event, sessionEvents);
        for (String rule : tier1Rules) {
            log.info("Tier1 rule triggered insuredId={} sessionId={} rule={}",
                    event.getInsuredId(), event.getSessionId(), rule);
            AnomalyAlert alert = AnomalyAlert.builder()
                    .insuredId(event.getInsuredId())
                    .sessionId(event.getSessionId())
                    .eventId(event.getId())
                    .eventTime(event.getCreatedAt())
                    .anomalyTier("TIER1")
                    .ruleType(rule)
                    .detectedAt(Instant.now())
                    .build();
            enqueuePendingAlert(alert, payload);
        }

        int tier2Every = Math.max(1, ruleProperties.getTier2EveryEvents());
        if (sessionEvents.size() % tier2Every == 0) {
            try {
                float[][] matrix = featureEngineeringService.buildFeatureMatrix(sessionEvents);
                log.info("Built feature matrix insuredId={} sessionId={} rows={} cols={}",
                        event.getInsuredId(),
                        event.getSessionId(),
                        matrix.length,
                        matrix.length == 0 ? 0 : matrix[0].length);
                double score = modelInferenceService.scoreAnomaly(matrix);
                redisCacheService.setJson(CacheKeys.aeScoreKey(event.getInsuredId()), score,
                        redisCacheProperties.getAeScore());
                log.info("Tier2 AE score insuredId={} sessionId={} score={} threshold={}",
                        event.getInsuredId(),
                        event.getSessionId(),
                        score,
                        anomalyThresholdLoader.getAnomalyThreshold().getThreshold());
                if (score > anomalyThresholdLoader.getAnomalyThreshold().getThreshold()) {
                    AnomalyAlert alert = AnomalyAlert.builder()
                            .insuredId(event.getInsuredId())
                            .sessionId(event.getSessionId())
                            .eventId(event.getId())
                            .eventTime(event.getCreatedAt())
                            .anomalyTier("TIER2")
                            .anomalyScore(score)
                            .detectedAt(Instant.now())
                            .build();
                    enqueuePendingAlert(alert, payload);
                }
            } catch (Exception e) {
                log.error("AE inference failed; skipping ML scoring for event {}", event.getId(), e);
                AnomalyAlert alert = AnomalyAlert.builder()
                        .insuredId(event.getInsuredId())
                        .sessionId(event.getSessionId())
                        .eventId(event.getId())
                        .eventTime(event.getCreatedAt())
                        .anomalyTier("ML_ERROR")
                        .detectedAt(Instant.now())
                        .build();
                alertPublisher.persistOnly(alert, payload);
            }
        }

        if (isSessionEnd(event)) {
            log.info("Session end detected insuredId={} sessionId={} action={}",
                    event.getInsuredId(), event.getSessionId(), event.getAction());
            handleSessionClose(event, sessionEvents, payload);
            sessionBufferService.deleteSession(event.getInsuredId(), event.getSessionId());
        }
    }

    private void handleSessionClose(AuditTrailEvent event, List<AuditTrailEvent> sessionEvents, String payload) {
        float[][] matrix = featureEngineeringService.buildFeatureMatrix(sessionEvents);
        Double aeScore = null;
        try {
            aeScore = modelInferenceService.scoreAnomaly(matrix);
            redisCacheService.setJson(CacheKeys.aeScoreKey(event.getInsuredId()), aeScore,
                    redisCacheProperties.getAeScore());
        } catch (Exception e) {
            log.error("AE inference failed at session close for session {}", event.getSessionId(), e);
            AnomalyAlert alert = AnomalyAlert.builder()
                    .insuredId(event.getInsuredId())
                    .sessionId(event.getSessionId())
                    .eventId(event.getId())
                    .eventTime(event.getCreatedAt())
                    .anomalyTier("ML_ERROR")
                    .ruleType("autoencoder")
                    .detectedAt(Instant.now())
                    .build();
            alertPublisher.persistOnly(alert, payload);
        }

        AnomalyTypeResult typeResult = null;
        try {
            typeResult = modelInferenceService.classifyType(matrix);
            if (typeResult != null) {
                log.info("Tier3 anomaly type insuredId={} sessionId={} type={} confidence={}",
                        event.getInsuredId(),
                        event.getSessionId(),
                        typeResult.getType(),
                        typeResult.getConfidence());
            }
        } catch (Exception e) {
            log.error("Type classification failed for session {}", event.getSessionId(), e);
            AnomalyAlert alert = AnomalyAlert.builder()
                    .insuredId(event.getInsuredId())
                    .sessionId(event.getSessionId())
                    .eventId(event.getId())
                    .eventTime(event.getCreatedAt())
                    .anomalyTier("ML_ERROR")
                    .ruleType("type_classifier")
                    .detectedAt(Instant.now())
                    .build();
            alertPublisher.persistOnly(alert, payload);
        }

        List<String> nextActions = new ArrayList<>();
        try {
            nextActions = modelInferenceService.predictNextActions(matrix, 3);
            log.info("Tier3 next actions insuredId={} sessionId={} nextActions={}",
                    event.getInsuredId(), event.getSessionId(), nextActions);
        } catch (Exception e) {
            log.error("Next action prediction failed for session {}", event.getSessionId(), e);
            AnomalyAlert alert = AnomalyAlert.builder()
                    .insuredId(event.getInsuredId())
                    .sessionId(event.getSessionId())
                    .eventId(event.getId())
                    .eventTime(event.getCreatedAt())
                    .anomalyTier("ML_ERROR")
                    .ruleType("next_action")
                    .detectedAt(Instant.now())
                    .build();
            alertPublisher.persistOnly(alert, payload);
        }

        SessionStats stats = statisticsService.computeSessionStats(sessionEvents);
        log.info("Session stats insuredId={} sessionId={} durationSec={} length={} uniqueActions={} koRate={} meanDelta={}",
                event.getInsuredId(),
                event.getSessionId(),
                stats.getSessionDurationSeconds(),
                stats.getSessionLength(),
                stats.getUniqueActionCount(),
                stats.getKoRate(),
                stats.getMeanDeltaSeconds());
        List<String> triggeredRules = evaluateTier1ForSession(sessionEvents);
        String pendingKey = CacheKeys.pendingAlertsKey(event.getInsuredId(), event.getSessionId());
        List<PendingAlert> pendingAlerts = redisCacheService.getJsonList(pendingKey, PendingAlert.class);
        boolean hasPendingAlerts = !pendingAlerts.isEmpty();
        boolean unknownType = isUnknownType(typeResult);
        if (unknownType) {
            log.info("Anomaly type UNKNOWN detected insuredId={} sessionId={}, suppressing anomaly alert.",
                    event.getInsuredId(), event.getSessionId());
        }

        boolean isAnomaly = (!triggeredRules.isEmpty()) ||
                (aeScore != null && aeScore > anomalyThresholdLoader.getAnomalyThreshold().getThreshold()) ||
                hasPendingAlerts;
        if (unknownType) {
            isAnomaly = false;
        }

        persistSessionAnalysis(event, sessionEvents, stats, aeScore, typeResult, nextActions, triggeredRules, isAnomaly);
        persistNextActions(event, nextActions);

        redisCacheService.setJson(CacheKeys.nextActionsKey(event.getInsuredId()), nextActions,
                redisCacheProperties.getNextActions());

        if (isAnomaly) {
            publishPendingAlerts(pendingAlerts);
            String sessionPayload = payload;
            try {
                sessionPayload = objectMapper.writeValueAsString(sessionEvents);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize full session payload for anomaly event {}", event.getSessionId(), e);
            }
            AnomalyAlert alert = AnomalyAlert.builder()
                    .insuredId(event.getInsuredId())
                    .sessionId(event.getSessionId())
                    .eventId(event.getId())
                    .eventTime(event.getCreatedAt())
                    .anomalyTier("TIER3")
                    .anomalyScore(aeScore)
                    .anomalyType(typeResult == null ? null : typeResult.getType())
                    .typeConfidence(typeResult == null ? null : typeResult.getConfidence())
                    .ruleType(triggeredRules.isEmpty() ? null : String.join(",", triggeredRules))
                    .detectedAt(Instant.now())
                    .build();
            alertPublisher.publish(alert, sessionPayload);
        } else {
            statisticsService.updateUserRiskProfile(event.getInsuredId());
        }
        redisCacheService.deleteKey(pendingKey);
    }

    private void persistSessionAnalysis(AuditTrailEvent event,
                                        List<AuditTrailEvent> sessionEvents,
                                        SessionStats stats,
                                        Double aeScore,
                                        AnomalyTypeResult typeResult,
                                        List<String> nextActions,
                                        List<String> triggeredRules,
                                        boolean isAnomaly) {
        SessionAnalysis analysis = new SessionAnalysis();
        analysis.setInsuredId(event.getInsuredId());
        analysis.setSessionId(event.getSessionId());
        List<AuditTrailEvent> ordered = new ArrayList<>(sessionEvents);
        ordered.sort((a, b) -> {
            if (a.getSequenceInSession() != null && b.getSequenceInSession() != null) {
                return a.getSequenceInSession().compareTo(b.getSequenceInSession());
            }
            if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
                return a.getCreatedAt().compareTo(b.getCreatedAt());
            }
            return 0;
        });
        analysis.setStartTime(ordered.isEmpty() ? null : ordered.get(0).getCreatedAt());
        analysis.setEndTime(ordered.isEmpty() ? null : ordered.get(ordered.size() - 1).getCreatedAt());
        analysis.setSessionLength(stats.getSessionLength());
        analysis.setSessionDurationSeconds(stats.getSessionDurationSeconds());
        analysis.setUniqueActionCount(stats.getUniqueActionCount());
        analysis.setKoRate(stats.getKoRate());
        analysis.setMeanDeltaSeconds(stats.getMeanDeltaSeconds());
        analysis.setActionDiversity(stats.getActionDiversity());
        try {
            analysis.setActionCountsJson(objectMapper.writeValueAsString(stats.getActionCounts()));
        } catch (JsonProcessingException e) {
            analysis.setActionCountsJson(null);
        }
        analysis.setAeScore(aeScore);
        analysis.setIsAnomaly(isAnomaly);
        analysis.setAnomalyType(typeResult == null ? null : typeResult.getType());
        analysis.setTypeConfidence(typeResult == null ? null : typeResult.getConfidence());
        try {
            analysis.setTop3NextActions(objectMapper.writeValueAsString(nextActions));
        } catch (JsonProcessingException e) {
            analysis.setTop3NextActions(null);
        }
        analysis.setRuleTriggered(!triggeredRules.isEmpty());
        analysis.setRuleType(triggeredRules.isEmpty() ? null : String.join(",", triggeredRules));
        analysis.setCreatedAt(Instant.now());
        SessionAnalysis saved = sessionAnalysisRepository.save(analysis);
        redisCacheService.setJson(CacheKeys.sessionAnalysisKey(event.getInsuredId(), event.getSessionId()),
                saved, redisCacheProperties.getLiveStats());
    }

    private void persistNextActions(AuditTrailEvent event, List<String> nextActions) {
        NextActionPrediction prediction = nextActionPredictionRepository.findByInsuredId(event.getInsuredId())
                .orElseGet(NextActionPrediction::new);
        prediction.setInsuredId(event.getInsuredId());
        prediction.setSessionId(event.getSessionId());
        prediction.setPredictedAt(Instant.now());
        try {
            prediction.setTop3ActionsJson(objectMapper.writeValueAsString(nextActions));
        } catch (JsonProcessingException e) {
            prediction.setTop3ActionsJson(null);
        }
        nextActionPredictionRepository.save(prediction);
    }

    private boolean isSessionEnd(AuditTrailEvent event) {
        int actionId = vocabService.actionId(event.getAction());
        if (sessionEndActionIds.contains(actionId)) {
            return true;
        }
        if (event.getAction() == null) {
            return false;
        }
        for (String action : ruleProperties.getSessionEndActions()) {
            if (action.equalsIgnoreCase(event.getAction())) {
                return true;
            }
        }
        return false;
    }

    private List<String> evaluateTier1(AuditTrailEvent event, List<AuditTrailEvent> sessionEvents) {
        List<String> rules = new ArrayList<>();
        if (isUnusualHour(event)) {
            rules.add("unusual_hour");
        }
        if (isSkipLogin(event)) {
            rules.add("skip_login");
        }
        if (hasRepeatedFail(sessionEvents)) {
            rules.add("repeated_fail");
        }
        return rules;
    }

    private List<String> evaluateTier1ForSession(List<AuditTrailEvent> sessionEvents) {
        List<String> rules = new ArrayList<>();
        for (AuditTrailEvent event : sessionEvents) {
            if (isUnusualHour(event)) {
                rules.add("unusual_hour");
                break;
            }
        }
        if (sessionEvents.stream().anyMatch(this::isSkipLogin)) {
            rules.add("skip_login");
        }
        if (hasRepeatedFail(sessionEvents)) {
            rules.add("repeated_fail");
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

    private boolean isSkipLogin(AuditTrailEvent event) {
        Integer sequence = event.getSequenceInSession();
        if (sequence == null || sequence != 1) {
            return false;
        }
        int actionId = vocabService.actionId(event.getAction());
        return !skipLoginAllowedIds.contains(actionId);
    }

    private boolean hasRepeatedFail(List<AuditTrailEvent> sessionEvents) {
        List<AuditTrailEvent> ordered = new ArrayList<>(sessionEvents);
        ordered.sort((a, b) -> {
            if (a.getSequenceInSession() != null && b.getSequenceInSession() != null) {
                return a.getSequenceInSession().compareTo(b.getSequenceInSession());
            }
            if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
                return a.getCreatedAt().compareTo(b.getCreatedAt());
            }
            return 0;
        });
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
        for (String allowed : ruleProperties.getRepeatedFail().getTypes()) {
            if (allowed.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    private void enqueuePendingAlert(AnomalyAlert alert, String payload) {
        PendingAlert pendingAlert = PendingAlert.builder()
                .alert(alert)
                .rawEventJson(payload)
                .build();
        String key = CacheKeys.pendingAlertsKey(alert.getInsuredId(), alert.getSessionId());
        redisCacheService.addToJsonList(key, pendingAlert, redisCacheProperties.getPendingAlerts());
        log.info("Queued pending alert insuredId={} sessionId={} tier={} rule={}",
                alert.getInsuredId(), alert.getSessionId(), alert.getAnomalyTier(), alert.getRuleType());
    }

    private void publishPendingAlerts(List<PendingAlert> pendingAlerts) {
        if (pendingAlerts == null || pendingAlerts.isEmpty()) {
            return;
        }
        for (PendingAlert pending : pendingAlerts) {
            if (pending == null || pending.getAlert() == null) {
                continue;
            }
            alertPublisher.publish(pending.getAlert(), pending.getRawEventJson());
        }
    }

    private boolean isUnknownType(AnomalyTypeResult typeResult) {
        return typeResult != null
                && typeResult.getType() != null
                && "UNKNOWN".equalsIgnoreCase(typeResult.getType());
    }
}
