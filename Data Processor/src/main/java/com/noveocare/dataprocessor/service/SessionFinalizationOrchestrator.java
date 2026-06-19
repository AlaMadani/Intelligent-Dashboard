package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import com.noveocare.dataprocessor.config.PerformanceProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionState;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.entity.SessionAnalysis;
import com.noveocare.dataprocessor.kafka.AlertPublisher;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
import com.noveocare.dataprocessor.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
public class SessionFinalizationOrchestrator {

    private final ObjectMapper objectMapper;
    private final AlertPublisher alertPublisher;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final StatisticsService statisticsService;
    private final DashboardSnapshotService dashboardSnapshotService;
    private final RedisSessionBufferService sessionBufferService;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties redisCacheProperties;
    private final FeatureEngineeringProperties featureEngineeringProperties;
    private final SessionFinalizationService finalizationService;
    private final PerformanceProperties performanceProperties;
    private final StringRedisTemplate redisTemplate;

    private final AtomicLong duplicateFinalizationSkipped = new AtomicLong();
    private final AtomicLong duplicateAlertsSkipped = new AtomicLong();
    private final AtomicLong duplicateLiveAlertsSkipped = new AtomicLong();
    private final AtomicLong duplicateUserAlertsSkipped = new AtomicLong();

    public void completeFinalization(SessionSummary summary, SessionInsight insight,
                                     List<AuditTrailEvent> enrichedEvents, List<String> triggeredRules,
                                     String endReason, boolean endedExplicitly) {
        long tStart = System.nanoTime();
        if (enrichedEvents == null || enrichedEvents.isEmpty()) {
            log.warn("No events to finalize for session {}/{}", summary.getInsuredId(), summary.getSessionId());
            return;
        }

        boolean alreadyFinalized = finalizationService.isSessionFinalized(summary.getInsuredId(), summary.getSessionId());
        if (alreadyFinalized) {
            duplicateFinalizationSkipped.incrementAndGet();
            log.warn("Session already finalized {}/{}, skipping duplicate finalization",
                    summary.getInsuredId(), summary.getSessionId());
            return;
        }

        long tState = System.nanoTime();
        SessionState sessionState = finalizationService.getSessionState(summary.getInsuredId(), summary.getSessionId());
        Instant endedAt = Instant.now();
        if (sessionState != null) {
            endedAt = sessionState.getEndedAt() != null ? sessionState.getEndedAt() : endedAt;
        }
        long stateFetchMs = (System.nanoTime() - tState) / 1_000_000L;

        Map<String, Object> finalizationMetadata = Map.of(
                "sessionEndReason", endReason,
                "sessionEndedExplicitly", endedExplicitly,
                "sessionEndedAt", endedAt.toString(),
                "sessionDurationMs", summary.getTotalDurationSeconds() != null ? summary.getTotalDurationSeconds() * 1000L : null,
                "sessionEventCount", summary.getTotalEvents()
        );

        insight = insight.toBuilder()
                .investigationPayload(enrichInvestigationPayload(insight, endReason, endedExplicitly, endedAt, summary))
                .build();

        long tAlertEval = System.nanoTime();
        boolean alertEligible = shouldAlert(insight);
        boolean alertPublished = false;
        boolean alreadyAlerted = hasDetectedAnomaly(summary.getInsuredId(), summary.getSessionId());
        if (alertEligible && !alreadyAlerted) {
            String alertReason = determineAlertReason(insight);
            publishAlert(summary, insight, enrichedEvents);
            alertPublished = true;
            log.info("ALERT_DECISION insuredId={} sessionId={} stage=FINALIZATION"
                            + " finalRisk={} riskTier={} triggeredRules={}"
                            + " sequenceRunBoth={} sequenceSelectedModel={}"
                            + " sequenceActuallyRanModels={} transformerContribution={} tcnContribution={}"
                            + " alertEligible=true alertPublished=true"
                            + " alertReason={} threshold={} alreadyAlerted=false",
                    summary.getInsuredId(), summary.getSessionId(),
                    insight.getFinalRiskScore(), insight.getRiskLevel(), insight.getTriggeredRules(),
                    insight.getSequenceRunBoth(), insight.getSelectedSequenceModel(),
                    insight.getSequenceActuallyRanModels(), insight.getModelContributions() == null ? null : insight.getModelContributions().get("transformer"),
                    insight.getModelContributions() == null ? null : insight.getModelContributions().get("tcn"),
                    alertReason, alertReason.contains("THRESHOLD") ? featureEngineeringProperties.getSessionAlertRiskThreshold() : "n/a");
        } else if (alertEligible && alreadyAlerted) {
            log.info("ALERT_DECISION insuredId={} sessionId={} stage=FINALIZATION"
                            + " finalRisk={} riskTier={} triggeredRules={}"
                            + " sequenceRunBoth={} sequenceSelectedModel={}"
                            + " sequenceActuallyRanModels={} transformerContribution={} tcnContribution={}"
                            + " alertEligible=true alertPublished=false"
                            + " alertReason=DUPLICATE_SKIPPED alreadyAlerted=true",
                    summary.getInsuredId(), summary.getSessionId(),
                    insight.getFinalRiskScore(), insight.getRiskLevel(), insight.getTriggeredRules(),
                    insight.getSequenceRunBoth(), insight.getSelectedSequenceModel(),
                    insight.getSequenceActuallyRanModels(), insight.getModelContributions() == null ? null : insight.getModelContributions().get("transformer"),
                    insight.getModelContributions() == null ? null : insight.getModelContributions().get("tcn"));
        } else {
            log.info("ALERT_DECISION insuredId={} sessionId={} stage=FINALIZATION"
                            + " finalRisk={} riskTier={} triggeredRules={}"
                            + " sequenceRunBoth={} sequenceSelectedModel={}"
                            + " sequenceActuallyRanModels={} transformerContribution={} tcnContribution={}"
                            + " alertEligible=false alertPublished=false"
                            + " alertReason=BELOW_THRESHOLD threshold={}",
                    summary.getInsuredId(), summary.getSessionId(),
                    insight.getFinalRiskScore(), insight.getRiskLevel(), insight.getTriggeredRules(),
                    insight.getSequenceRunBoth(), insight.getSelectedSequenceModel(),
                    insight.getSequenceActuallyRanModels(), insight.getModelContributions() == null ? null : insight.getModelContributions().get("transformer"),
                    insight.getModelContributions() == null ? null : insight.getModelContributions().get("tcn"),
                    featureEngineeringProperties.getSessionAlertRiskThreshold());
        }
        long alertEvalMs = (System.nanoTime() - tAlertEval) / 1_000_000L;

        long tPersist = System.nanoTime();
        persistSessionAnalysis(summary, insight, triggeredRules);
        long persistMs = (System.nanoTime() - tPersist) / 1_000_000L;

        long tRiskProfile = System.nanoTime();
        statisticsService.updateUserRiskProfile(summary.getInsuredId());
        long riskProfileMs = (System.nanoTime() - tRiskProfile) / 1_000_000L;

        long tCache = System.nanoTime();
        if (!performanceProperties.getHotPath().isSkipDashboardRefreshInListener()) {
            dashboardSnapshotService.refreshAll();
        } else {
            dashboardSnapshotService.markRiskySessionsDirty();
            dashboardSnapshotService.markOverviewDirty();
        }
        dashboardSnapshotService.removeSessionInsight(summary.getInsuredId(), summary.getSessionId());
        sessionBufferService.deleteSession(summary.getInsuredId(), summary.getSessionId());
        redisCacheService.deleteKey(CacheKeys.detectedAnomalyKey(summary.getInsuredId(), summary.getSessionId()));
        finalizationService.finalizeSession(summary.getInsuredId(), summary.getSessionId(), endReason, endedExplicitly);
        long cacheWriteMs = (System.nanoTime() - tCache) / 1_000_000L;

        long totalMs = (System.nanoTime() - tStart) / 1_000_000L;
        log.info("SESSION_FINALIZATION_TIMING insuredId={} sessionId={} reason={} explicit={}"
                        + " events={} usedFullHistory=true"
                        + " stateFetchMs={} alertEvalMs={} persistMs={} riskProfileMs={}"
                        + " cacheWriteMs={} totalMs={} alertPublished={} risk={}",
                summary.getInsuredId(), summary.getSessionId(), endReason, endedExplicitly,
                enrichedEvents.size(),
                stateFetchMs, alertEvalMs, persistMs, riskProfileMs,
                cacheWriteMs, totalMs, alertPublished, insight.getFinalRiskScore());
    }

    public boolean shouldAlert(SessionInsight insight) {
        return insight.isAnomaly()
                || (insight.getFinalRiskScore() != null
                && insight.getFinalRiskScore() >= featureEngineeringProperties.getSessionAlertRiskThreshold())
                || (insight.getEnsembleRiskScore() != null
                && insight.getEnsembleRiskScore() >= featureEngineeringProperties.getSessionAlertRiskThreshold());
    }

    public boolean hasDetectedAnomaly(String insuredId, String sessionId) {
        return redisCacheService.hasKey(CacheKeys.detectedAnomalyKey(insuredId, sessionId));
    }

    public void persistSessionAnalysis(SessionSummary summary, SessionInsight insight, List<String> triggeredRules) {
        long startMs = System.currentTimeMillis();
        sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(
                summary.getInsuredId(), summary.getSessionId()).ifPresentOrElse(
                existing -> {
                    existing.setEndTime(summary.getSessionEnd());
                    existing.setTotalEvents(summary.getTotalEvents());
                    existing.setSessionDurationSeconds(summary.getTotalDurationSeconds());
                    existing.setUniqueActions(summary.getUniqueActions());
                    existing.setChurnProbability(insight.getChurnProbability());
                    existing.setPersonaCluster(insight.getPersonaCluster());
                    updateSessionAnalysisScores(existing, summary, insight, triggeredRules);
                    long saveStart = System.currentTimeMillis();
                    sessionAnalysisRepository.save(existing);
                    long saveMs = System.currentTimeMillis() - saveStart;
                    if (saveMs > 1000) {
                        log.warn("SQL slow: session_analysis save (update) took {}ms for {}/{}",
                                saveMs, summary.getInsuredId(), summary.getSessionId());
                    }
                    logEvidenceSqlWrite(summary, insight);
                },
                () -> {
                    SessionAnalysis entity = new SessionAnalysis();
                    entity.setInsuredId(summary.getInsuredId());
                    entity.setSessionId(summary.getSessionId());
                    entity.setCountryCode(summary.getCountryCode());
                    entity.setStartTime(summary.getSessionStart());
                    entity.setEndTime(summary.getSessionEnd());
                    entity.setTotalEvents(summary.getTotalEvents());
                    entity.setSessionDurationSeconds(summary.getTotalDurationSeconds());
                    entity.setUniqueActions(summary.getUniqueActions());
                    updateSessionAnalysisScores(entity, summary, insight, triggeredRules);
                    long saveStart = System.currentTimeMillis();
                    sessionAnalysisRepository.save(entity);
                    long saveMs = System.currentTimeMillis() - saveStart;
                    if (saveMs > 1000) {
                        log.warn("SQL slow: session_analysis save (insert) took {}ms for {}/{}",
                                saveMs, summary.getInsuredId(), summary.getSessionId());
                    }
                    logEvidenceSqlWrite(summary, insight);
                }
        );
        long elapsedMs = System.currentTimeMillis() - startMs;
        if (elapsedMs > 1000) {
            log.warn("SQL slow: session_analysis upsert took {}ms for {}/{}",
                    elapsedMs, summary.getInsuredId(), summary.getSessionId());
        }
        long redisStart = System.currentTimeMillis();
        redisCacheService.setJson(
                CacheKeys.sessionAnalysisKey(summary.getInsuredId(), summary.getSessionId()),
                insight,
                redisCacheProperties.getLiveStats());
        long redisMs = System.currentTimeMillis() - redisStart;
        if (redisMs > 500) {
            log.warn("Redis slow: session_analysis set took {}ms for {}/{}",
                    redisMs, summary.getInsuredId(), summary.getSessionId());
        }
    }

    private void logEvidenceSqlWrite(SessionSummary summary, SessionInsight insight) {
        try {
            Map<String, Object> ev = insight.getLlmExplanationEvidencePayload();
            if (ev != null) {
                log.info("LLM_EVIDENCE_PAYLOAD_WRITE eventId={} insuredId={} sessionId={} hash={} version={} target=sql",
                        ev.get("eventId"), summary.getInsuredId(), summary.getSessionId(),
                        ev.get("evidenceHash"), ev.get("evidenceVersion"));
            }
        } catch (Exception e) {
            log.warn("LLM_EVIDENCE_PAYLOAD_WRITE_FAILED insuredId={} sessionId={} msg={}",
                    summary.getInsuredId(), summary.getSessionId(), e.getMessage());
        }
    }

    private void updateSessionAnalysisScores(SessionAnalysis entity, SessionSummary summary, SessionInsight insight, List<String> triggeredRules) {
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
    }

    public void publishAlert(SessionSummary summary, SessionInsight insight, List<AuditTrailEvent> enrichedEvents) {
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
                .riskTier(insight.getRiskLevel())
                .riskScale(insight.getRiskScale())
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
                .eventMetadata(buildEventMetadata(insight, lastEvent))
                .sequenceEvidence(buildSequenceEvidence(insight))
                .tabularEvidence(buildTabularEvidence(insight))
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
        liveAlert.put("riskTier", alert.getRiskTier());
        liveAlert.put("riskScale", alert.getRiskScale());
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
        liveAlert.put("eventMetadata", alert.getEventMetadata());
        liveAlert.put("sequenceEvidence", alert.getSequenceEvidence());
        liveAlert.put("tabularEvidence", alert.getTabularEvidence());

        String eventId = alert.getEventId() != null ? alert.getEventId() : "";

        if (tryAddToLiveAlertsSet(eventId)) {
            redisCacheService.addToJsonList(CacheKeys.liveAlertsV36Key(), liveAlert, redisCacheProperties.getLiveStats());
        } else {
            duplicateLiveAlertsSkipped.incrementAndGet();
        }

        if (tryAddToUserAlertsSet(alert.getInsuredId(), eventId)) {
            redisCacheService.addToJsonList(CacheKeys.userAlertsKey(alert.getInsuredId()), liveAlert, redisCacheProperties.getLiveStats());
        } else {
            duplicateUserAlertsSkipped.incrementAndGet();
        }

        if ("CRITICAL".equalsIgnoreCase(alert.getRiskLevel())) {
            if (tryAddToCriticalAlertsSet(eventId)) {
                redisCacheService.addToJsonList(CacheKeys.criticalAlertsV36Key(), liveAlert, redisCacheProperties.getLiveStats());
            }
        }

        if (insight.getLlmExplanationEvidencePayload() != null) {
            redisCacheService.setJson(CacheKeys.alertLlmEvidenceKey(eventId),
                    insight.getLlmExplanationEvidencePayload(),
                    redisCacheProperties.getSessionInsight());
            Map<String, Object> ev = insight.getLlmExplanationEvidencePayload();
            log.info("LLM_EVIDENCE_PAYLOAD_WRITE eventId={} insuredId={} sessionId={} hash={} version={} target=redis",
                    eventId, alert.getInsuredId(), alert.getSessionId(),
                    ev.get("evidenceHash"), ev.get("evidenceVersion"));
        }
        if (insight.getInvestigationPayload() != null) {
            redisCacheService.setJson(CacheKeys.alertInvestigationKey(eventId),
                    insight.getInvestigationPayload(),
                    redisCacheProperties.getSessionInsight());
        }
    }

    private boolean tryAddToLiveAlertsSet(String eventId) {
        if (eventId == null || eventId.isBlank()) return false;
        Long added = redisTemplate.opsForSet().add(CacheKeys.liveAlertsEventIdsKey(), eventId);
        redisTemplate.expire(CacheKeys.liveAlertsEventIdsKey(), redisCacheProperties.getLiveStats());
        return added != null && added > 0;
    }

    private boolean tryAddToUserAlertsSet(String insuredId, String eventId) {
        if (eventId == null || eventId.isBlank() || insuredId == null || insuredId.isBlank()) return false;
        String key = CacheKeys.userAlertsEventIdsKey(insuredId);
        Long added = redisTemplate.opsForSet().add(key, eventId);
        redisTemplate.expire(key, redisCacheProperties.getLiveStats());
        return added != null && added > 0;
    }

    private boolean tryAddToCriticalAlertsSet(String eventId) {
        if (eventId == null || eventId.isBlank()) return false;
        Long added = redisTemplate.opsForSet().add(CacheKeys.criticalAlertsEventIdsKey(), eventId);
        redisTemplate.expire(CacheKeys.criticalAlertsEventIdsKey(), redisCacheProperties.getLiveStats());
        return added != null && added > 0;
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

    private Map<String, Object> enrichInvestigationPayload(SessionInsight insight, String endReason,
                                                            boolean endedExplicitly, Instant endedAt,
                                                            SessionSummary summary) {
        Map<String, Object> existing = insight.getInvestigationPayload();
        if (existing == null) {
            existing = new LinkedHashMap<>();
        } else {
            existing = new LinkedHashMap<>(existing);
        }
        existing.put("sessionEndReason", endReason);
        existing.put("sessionEndedExplicitly", endedExplicitly);
        existing.put("sessionEndedAt", endedAt.toString());
        existing.put("sessionDurationMs", summary.getTotalDurationSeconds() != null ? summary.getTotalDurationSeconds() * 1000L : null);
        existing.put("sessionEventCount", summary.getTotalEvents());
        return existing;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String determineAlertReason(SessionInsight insight) {
        if (insight.isAnomaly()) {
            if (insight.getTriggeredRules() != null && !insight.getTriggeredRules().isEmpty()) {
                return "RULE_AND_RISK";
            }
            return "ANOMALY_THRESHOLD";
        }
        if (insight.getFinalRiskScore() != null
                && insight.getFinalRiskScore() >= featureEngineeringProperties.getSessionAlertRiskThreshold()) {
            return "FINAL_RISK_THRESHOLD";
        }
        if (insight.getTriggeredRules() != null && !insight.getTriggeredRules().isEmpty()) {
            return "RULE_TRIGGER";
        }
        return "UNKNOWN";
    }

    private java.util.Map<String, Object> buildEventMetadata(SessionInsight insight, AuditTrailEvent event) {
        java.util.Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("eventId", event.getId());
        meta.put("action", event.getAction());
        meta.put("apiTemplate", event.getApiTemplate());
        meta.put("apiFamily", event.getApiFamily());
        meta.put("controller", event.getController());
        meta.put("page", event.getPage());
        meta.put("country", firstNonBlank(event.getIpCountry(), event.getCountryCode()));
        meta.put("device", event.getDevice());
        meta.put("browser", event.getBrowser());
        meta.put("os", event.getOs());
        meta.put("httpMethod", event.getHttpMethod());
        meta.put("status", event.getStatus());
        meta.put("route", event.getRoute());
        meta.put("eventTime", event.getCreatedAt());
        meta.put("available", true);
        return meta;
    }

    private java.util.Map<String, Object> buildSequenceEvidence(SessionInsight insight) {
        java.util.Map<String, Object> seq = new LinkedHashMap<>();
        seq.put("contextAvailable", insight.getSequenceContextAvailable());
        seq.put("selectedModel", insight.getSelectedSequenceModel());
        seq.put("windowSize", null);
        seq.put("anomalyScore", insight.getSequenceAnomalyScore());
        seq.put("categoricalScore", insight.getSequenceCategoricalScore());
        seq.put("continuousScore", insight.getSequenceContinuousScore());
        seq.put("contextScore", insight.getSequenceContextScore());
        seq.put("surpriseScoreRaw", insight.getTransformerScore() != null ? insight.getTransformerScore() : insight.getTcnScore());
        seq.put("riskScore100", insight.getTransformerRiskScore100() != null ? insight.getTransformerRiskScore100() : insight.getTcnRiskScore100());
        seq.put("latencyMs", insight.getSequenceLatencyMs());
        seq.put("runBoth", insight.getSequenceRunBoth());
        seq.put("actuallyRanModels", insight.getSequenceActuallyRanModels());
        seq.put("transformerUsedInFusion", insight.getTransformerUsedInFusion());
        seq.put("tcnUsedInFusion", insight.getTcnUsedInFusion());
        seq.put("available", insight.getSequenceAnomalyScore() != null || insight.getSelectedSequenceModel() != null);
        return seq;
    }

    private java.util.Map<String, Object> buildTabularEvidence(SessionInsight insight) {
        java.util.Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("availableModels", insight.getAvailableTabularModels());
        tab.put("unavailableModels", insight.getUnavailableTabularModels());
        tab.put("featureWarnings", insight.getTabularWarnings());
        tab.put("available", insight.getAvailableTabularModels() != null && !insight.getAvailableTabularModels().isEmpty());
        return tab;
    }

    public long getDuplicateFinalizationSkipped() {
        return duplicateFinalizationSkipped.get();
    }

    public long getDuplicateAlertsSkipped() {
        return duplicateAlertsSkipped.get();
    }

    public long getDuplicateLiveAlertsSkipped() {
        return duplicateLiveAlertsSkipped.get();
    }

    public long getDuplicateUserAlertsSkipped() {
        return duplicateUserAlertsSkipped.get();
    }
}