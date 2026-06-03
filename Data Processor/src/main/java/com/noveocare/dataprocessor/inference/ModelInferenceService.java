package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.TextNormalization;
import com.noveocare.dataprocessor.ai.churn.ChurnInferenceService;
import com.noveocare.dataprocessor.ai.churn.ChurnPrediction;
import com.noveocare.dataprocessor.ai.explanation.LlmEvidencePayloadService;
import com.noveocare.dataprocessor.ai.forecast.ForecastPrediction;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.ai.persona.PersonaAssignment;
import com.noveocare.dataprocessor.ai.persona.PersonaRuntimeService;
import com.noveocare.dataprocessor.ai.sequence.EncodedSequenceEvent;
import com.noveocare.dataprocessor.ai.sequence.SequenceAnomalyScoringService;
import com.noveocare.dataprocessor.ai.sequence.SequenceInferenceResult;
import com.noveocare.dataprocessor.ai.sequence.SequenceModelKind;
import com.noveocare.dataprocessor.ai.sequence.SequenceOnnxInferenceService;
import com.noveocare.dataprocessor.ai.sequence.SequencePreprocessingService;
import com.noveocare.dataprocessor.ai.sequence.SequenceScoreResult;
import com.noveocare.dataprocessor.ai.sequence.SequenceWindow;
import com.noveocare.dataprocessor.ai.sequence.SequenceWindowService;
import com.noveocare.dataprocessor.ai.sequence.SequenceWindowState;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyFeatureService;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyFeatureVector;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyInferenceService;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyResult;
import com.noveocare.dataprocessor.config.AiRiskScoringProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AnomalyTypeResult;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.FeatureContribution;
import com.noveocare.dataprocessor.dto.SessionInsight;
import com.noveocare.dataprocessor.dto.SessionSummary;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ModelInferenceService {

    private final AiSequenceProperties sequenceProperties;
    private final AiRiskScoringProperties riskProperties;
    private final SequencePreprocessingService preprocessingService;
    private final SequenceWindowService windowService;
    private final SequenceOnnxInferenceService onnxInferenceService;
    private final SequenceAnomalyScoringService scoringService;
    private final LoadSheddingService loadSheddingService;
    private final TabularAnomalyFeatureService tabularFeatureService;
    private final TabularAnomalyInferenceService tabularAnomalyInferenceService;
    private final RuleRiskScoringService ruleRiskScoringService;
    private final RiskFusionServiceV36 riskFusionService;
    private final AnomalyTypeAttributionServiceV36 anomalyTypeAttributionService;
    private final PersonaRuntimeService personaRuntimeService;
    private final ChurnInferenceService churnInferenceService;
    private final ForecastRuntimeService forecastRuntimeService;
    private final LlmEvidencePayloadService llmEvidencePayloadService;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties redisCacheProperties;
    private final ModelHealthService modelHealthService;

    public SessionInsight infer(SessionSummary summary, List<AuditTrailEvent> enrichedEvents, List<String> triggeredRules) {
        return infer(summary, enrichedEvents, triggeredRules, 0L);
    }

    public SessionInsight infer(SessionSummary summary,
                                List<AuditTrailEvent> enrichedEvents,
                                List<String> triggeredRules,
                                long kafkaLag) {
        AuditTrailEvent currentEvent = latestEvent(enrichedEvents);
        List<String> warnings = new ArrayList<>();
        if (currentEvent == null) {
            warnings.add("missing_current_event");
            return rulesOnlyInsight(summary, null, triggeredRules, warnings, "missing_current_event");
        }

        SequenceWindowState previousState = windowService.load(summary.getSessionId());
        EncodedSequenceEvent currentEncoded = preprocessingService.encode(currentEvent, windowService.latestTimestamp(previousState));
        warnings.addAll(currentEncoded.getWarnings() == null ? List.of() : currentEncoded.getWarnings());

        RuleRiskResult ruleRiskResult = ruleRiskScoringService.evaluate(summary, enrichedEvents, triggeredRules);
        double ruleRiskScore = ruleRiskResult.getRuleRiskScore();
        SequenceModelKind selectedModel = loadSheddingService.selectModel(kafkaLag);
        SequenceScoreResult selectedScore = SequenceScoreResult.unavailable(List.of());
        SequenceScoreResult transformerScoreResult = null;
        SequenceScoreResult tcnScoreResult = null;
        String fallbackMode = "normal";

        try {
            if (!currentEncoded.isSchemaValid()) {
                warnings.add("sequence_schema_invalid");
                fallbackMode = "schema_invalid_rules_only";
            } else if (selectedModel == SequenceModelKind.RULES_ONLY) {
                warnings.add("load_shedding_rules_only");
                fallbackMode = "rules_only";
            } else if (previousState.realEventCount() < sequenceProperties.getMinContextEvents()) {
                warnings.add("insufficient_sequence_context");
                fallbackMode = "insufficient_context";
            } else {
                SequenceWindow previousWindow = windowService.toWindow(previousState);
                if (sequenceProperties.isRunBoth() && selectedModel == SequenceModelKind.TRANSFORMER) {
                    SequenceScoreResult transformer = runAndScore(SequenceModelKind.TRANSFORMER, previousWindow, currentEncoded);
                    SequenceScoreResult tcn = runAndScore(SequenceModelKind.TCN, previousWindow, currentEncoded);
                    transformerScoreResult = transformer;
                    tcnScoreResult = tcn;
                    selectedScore = chooseMaxRisk(transformer, tcn);
                } else {
                    if (selectedModel == SequenceModelKind.TCN) {
                        warnings.add("load_shedding_tcn_mode");
                        fallbackMode = "tcn";
                    }
                    selectedScore = runAndScore(selectedModel, previousWindow, currentEncoded);
                    if (selectedModel == SequenceModelKind.TRANSFORMER) {
                        transformerScoreResult = selectedScore;
                    } else if (selectedModel == SequenceModelKind.TCN) {
                        tcnScoreResult = selectedScore;
                    }
                }
            }
            modelHealthService.recordInference(fallbackMode);
            recordSequenceRuntimeHealth(transformerScoreResult, tcnScoreResult);
        } catch (Exception ex) {
            warnings.add("sequence_runtime_failed");
            modelHealthService.recordError("sequence_runtime_failed");
            if (selectedModel == SequenceModelKind.TRANSFORMER) {
                modelHealthService.recordRuntimeError("transformer", "sequence_runtime_failed");
            } else if (selectedModel == SequenceModelKind.TCN) {
                modelHealthService.recordRuntimeError("tcn", "sequence_runtime_failed");
            }
            log.warn("Sequence inference failed for session {}", summary.getSessionId(), ex);
        } finally {
            if (currentEncoded.isSchemaValid() || !sequenceProperties.isStrictSchema()) {
                windowService.appendAndSave(summary.getSessionId(), currentEncoded);
            }
        }

        warnings.addAll(selectedScore.getWarnings() == null ? List.of() : selectedScore.getWarnings());
        TabularAnomalyResult tabularResult = scoreTabular(previousState, currentEncoded, selectedModel, warnings);
        recordTabularRuntimeHealth(tabularResult);
        warnings.addAll(tabularResult.getModelWarnings() == null ? List.of() : tabularResult.getModelWarnings());

        RiskFusionResult fusion = riskFusionService.fuse(
                tabularResult,
                transformerScoreResult == null ? null : transformerScoreResult.getAiRiskScore(),
                tcnScoreResult == null ? null : tcnScoreResult.getAiRiskScore(),
                ruleRiskResult,
                aggregationBoost(summary, ruleRiskResult));
        warnings.addAll(fusion.getFusionWarnings() == null ? List.of() : fusion.getFusionWarnings());

        AnomalyTypeAttributionResult anomalyType = anomalyTypeAttributionService.attribute(
                tabularResult,
                transformerScoreResult,
                tcnScoreResult,
                ruleRiskResult,
                currentEvent,
                summary,
                fusion);

        PersonaAssignment persona = personaRuntimeService.unknown("persona_embedding_unavailable");
        warnings.addAll(persona.getWarnings());
        ChurnPrediction churn = churnInferenceService.predict(
                summary,
                enrichedEvents,
                fusion.getFinalRiskScore() >= riskProperties.getMediumThreshold());
        recordChurnRuntimeHealth(churn);
        warnings.addAll(churn.getWarnings());
        ForecastPrediction forecast = forecastRuntimeService.forecast(LocalDate.now(ZoneOffset.UTC));
        recordForecastRuntimeHealth(forecast);
        warnings.addAll(forecast.getWarnings());
        modelHealthService.publish();

        double aiRiskScore = Math.max(
                valueOrZero(tabularResult.getXgboostAnomalyScore100()),
                Math.max(valueOrZero(tabularResult.getLightgbmAlertScore100()), valueOrZero(selectedScore.getAiRiskScore())));
        double finalRiskScore = fusion.getFinalRiskScore();
        Map<String, Object> modelScores = buildModelScores(tabularResult, transformerScoreResult, tcnScoreResult, ruleRiskResult);
        Map<String, Object> modelContributions = buildModelContributions(fusion);
        Map<String, Object> forecastContext = buildForecastContext(forecast);
        warnings.add("anomaly_probability_is_final_risk_normalized_not_sequence_probability");

        SessionInsight insight = SessionInsight.builder()
                .insuredId(summary.getInsuredId())
                .sessionId(summary.getSessionId())
                .computedAt(java.time.Instant.now())
                .binaryAnomaly(aiRiskScore >= riskProperties.getMediumThreshold())
                .anomaly(finalRiskScore >= riskProperties.getMediumThreshold())
                .anomalyScore(finalRiskScore)
                .anomalyProbability(finalRiskScore / 100.0)
                .binaryDetectorArtifact("risk_fusion_v3_6_1")
                .anomalyType(anomalyType.getAnomalyType())
                .anomalyTypeConfidence(anomalyType.getAnomalyTypeConfidence())
                .churnProbability(churn.getProbability())
                .personaCluster(persona.getClusterId())
                .ensembleRiskScore(finalRiskScore)
                .riskLevel(fusion.getRiskLevel())
                .contextTags(buildContextTags(summary, ruleRiskResult.getTriggeredRules(), selectedScore))
                .triggeredRules(ruleRiskResult.getTriggeredRules())
                .warnings(warnings.stream().distinct().toList())
                .topContributingFeatures(toFeatureContributions(selectedScore))
                .explainabilityText(buildEvidenceSummary(selectedScore, ruleRiskResult, finalRiskScore, fusion.getFallbackMode()))
                .sequenceModelPrimary(sequenceProperties.getPrimaryModel())
                .sequenceModelFast(sequenceProperties.getFastModel())
                .selectedSequenceModel(selectedScore.getModelKind())
                .sequenceModelArtifact(selectedScore.getModelArtifact())
                .transformerScore(transformerScoreResult == null ? null : transformerScoreResult.getSequenceAnomalyScore())
                .transformerRiskScore100(transformerScoreResult == null ? null : transformerScoreResult.getAiRiskScore())
                .tcnScore(tcnScoreResult == null ? null : tcnScoreResult.getSequenceAnomalyScore())
                .tcnRiskScore100(tcnScoreResult == null ? null : tcnScoreResult.getAiRiskScore())
                .sequenceAnomalyScore(selectedScore.getSequenceAnomalyScore())
                .sequenceCategoricalScore(selectedScore.getCategoricalScore())
                .sequenceContinuousScore(selectedScore.getContinuousScore())
                .sequenceContextScore(selectedScore.getContextScore())
                .sequenceLatencyMs(selectedScore.getLatencyMillis())
                .sequenceContextAvailable(previousState.realEventCount() >= sequenceProperties.getMinContextEvents())
                .aiRiskScore(aiRiskScore)
                .ruleRiskScore(ruleRiskScore)
                .ruleContributions(ruleRiskResult.getRuleContributions())
                .ruleEvidence(ruleRiskResult.getRuleEvidence())
                .finalRiskScore(finalRiskScore)
                .sequenceTopContributions(selectedScore.getTopContributingFields())
                .anomalyTypeSource(anomalyType.getAnomalyTypeSource())
                .anomalyTypeEvidence(anomalyType.getAnomalyTypeEvidenceJson())
                .personaLabel(persona.getLabel())
                .personaSource(persona.getSource())
                .personaConfidence(persona.getConfidence())
                .personaWarnings(persona.getWarnings())
                .churnRiskLevel(churn.getRiskLevel())
                .churnModelName(churn.getModelName())
                .churnModelArtifact(churn.getModelArtifact())
                .churnFeatureWarnings(churn.getWarnings())
                .forecastTotalEvents(forecast.getTotalEventsForecast())
                .forecastAnomalyRate(forecast.getAnomalyRateForecast())
                .forecastExpectedAlertVolume(forecast.getExpectedAlertVolume())
                .forecastTotalEventsModel(forecast.getTotalEventsModelName())
                .forecastAnomalyRateModel(forecast.getAnomalyRateModelName())
                .forecastContext(forecastContext)
                .xgboostAnomalyScore(tabularResult.getXgboostAnomalyScore())
                .xgboostAnomalyScore100(tabularResult.getXgboostAnomalyScore100())
                .xgboostArtifact(tabularResult.getXgboostArtifact())
                .lightgbmAlertScore(tabularResult.getLightgbmAlertScore())
                .lightgbmAlertScore100(tabularResult.getLightgbmAlertScore100())
                .lightgbmArtifact(tabularResult.getLightgbmArtifact())
                .catboostAnomalyScore(tabularResult.getCatboostAnomalyScore())
                .catboostAnomalyScore100(tabularResult.getCatboostAnomalyScore100())
                .oneClassSvmNoveltyScoreRaw(tabularResult.getOneClassSvmNoveltyScoreRaw())
                .oneClassSvmNoveltyScore100(tabularResult.getOneClassSvmNoveltyScore100())
                .availableTabularModels(tabularResult.getAvailableModels())
                .unavailableTabularModels(tabularResult.getUnavailableModels())
                .tabularWarnings(tabularResult.getModelWarnings())
                .businessContextScore(ruleRiskResult.getBusinessContextScore())
                .aggregationBoost(fusion.getAggregationBoost())
                .riskFusionWeights(fusion.getUsedWeights())
                .unavailableModelWeights(fusion.getUnavailableModelWeights())
                .modelScores(modelScores)
                .modelContributions(modelContributions)
                .fallbackMode(fusion.getFallbackMode())
                .modelArtifacts(buildArtifactNames(selectedScore, tabularResult, churn, forecast))
                .build();

        Map<String, Object> evidencePayload = llmEvidencePayloadService.build(summary, currentEvent, insight);
        insight = insight.toBuilder()
                .llmExplanationEvidencePayload(evidencePayload)
                .investigationPayload(buildInvestigationPayload(summary, currentEvent, insight, evidencePayload))
                .build();

        cacheRuntimeScores(summary, insight);
        return insight;
    }

    public SessionInsight inferLightweight(SessionSummary summary,
                                           List<AuditTrailEvent> enrichedEvents,
                                           List<String> triggeredRules,
                                           String reason) {
        List<String> warnings = new ArrayList<>();
        warnings.add(reason == null || reason.isBlank() ? "rules_only" : reason);
        return rulesOnlyInsight(summary, latestEvent(enrichedEvents), triggeredRules, warnings, reason);
    }

    private SequenceScoreResult runAndScore(SequenceModelKind modelKind, SequenceWindow previousWindow, EncodedSequenceEvent target) {
        SequenceInferenceResult inference = onnxInferenceService.infer(modelKind, previousWindow);
        return scoringService.score(inference, target);
    }

    private SequenceScoreResult chooseMaxRisk(SequenceScoreResult left, SequenceScoreResult right) {
        double leftRisk = left.getAiRiskScore() == null ? 0.0 : left.getAiRiskScore();
        double rightRisk = right.getAiRiskScore() == null ? 0.0 : right.getAiRiskScore();
        return leftRisk >= rightRisk ? left : right;
    }

    private void recordSequenceRuntimeHealth(SequenceScoreResult transformerScoreResult,
                                             SequenceScoreResult tcnScoreResult) {
        if (transformerScoreResult != null) {
            recordRuntimeHealth("transformer", transformerScoreResult.isAvailable(), transformerScoreResult.getWarnings());
        }
        if (tcnScoreResult != null) {
            recordRuntimeHealth("tcn", tcnScoreResult.isAvailable(), tcnScoreResult.getWarnings());
        }
    }

    private void recordTabularRuntimeHealth(TabularAnomalyResult tabularResult) {
        if (tabularResult == null) {
            for (String runtime : List.of("xgboost", "lightgbm", "catboost", "oneclasssvm")) {
                modelHealthService.recordRuntimeError(runtime, "tabular_result_missing");
            }
            return;
        }
        List<String> available = tabularResult.getAvailableModels() == null ? List.of() : tabularResult.getAvailableModels();
        List<String> unavailable = tabularResult.getUnavailableModels() == null ? List.of() : tabularResult.getUnavailableModels();
        for (String runtime : available) {
            modelHealthService.recordRuntimeSuccess(runtime);
        }
        for (String runtime : unavailable) {
            modelHealthService.recordRuntimeError(runtime, firstWarning(tabularResult.getModelWarnings(), runtime + "_unavailable"));
        }
    }

    private void recordChurnRuntimeHealth(ChurnPrediction churn) {
        if (churn != null && churn.isAvailable()) {
            modelHealthService.recordRuntimeSuccess("churn");
            return;
        }
        modelHealthService.recordRuntimeError("churn", firstWarning(churn == null ? null : churn.getWarnings(), "churn_model_unavailable"));
    }

    private void recordForecastRuntimeHealth(ForecastPrediction forecast) {
        if (forecastRuntimeService.ridgeLoaded()
                && forecast != null
                && !"fallback".equalsIgnoreCase(forecast.getAnomalyRateModelName())) {
            modelHealthService.recordRuntimeSuccess("forecast_ridge");
        } else {
            modelHealthService.recordRuntimeError("forecast_ridge", firstWarning(forecast == null ? null : forecast.getWarnings(), "forecast_ridge_unavailable"));
        }
        if (forecastRuntimeService.xgboostLoaded()
                && forecast != null
                && !"fallback".equalsIgnoreCase(forecast.getTotalEventsModelName())) {
            modelHealthService.recordRuntimeSuccess("forecast_xgboost");
        } else {
            modelHealthService.recordRuntimeError("forecast_xgboost", firstWarning(forecast == null ? null : forecast.getWarnings(), "forecast_xgboost_unavailable"));
        }
    }

    private void recordRuntimeHealth(String runtime, boolean available, List<String> warnings) {
        if (available) {
            modelHealthService.recordRuntimeSuccess(runtime);
        } else {
            modelHealthService.recordRuntimeError(runtime, firstWarning(warnings, runtime + "_unavailable"));
        }
    }

    private String firstWarning(List<String> warnings, String fallback) {
        if (warnings == null || warnings.isEmpty()) {
            return fallback;
        }
        return warnings.stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private SessionInsight rulesOnlyInsight(SessionSummary summary,
                                            AuditTrailEvent currentEvent,
                                            List<String> triggeredRules,
                                            List<String> warnings,
                                            String reason) {
        RuleRiskResult ruleRiskResult = ruleRiskScoringService.evaluate(summary, List.of(), triggeredRules);
        RiskFusionResult fusion = riskFusionService.fuse(
                TabularAnomalyResult.unavailable(List.of(reason == null ? "rules_only" : reason)),
                null,
                null,
                ruleRiskResult,
                0.0);
        AnomalyTypeAttributionResult anomalyType = anomalyTypeAttributionService.attribute(
                TabularAnomalyResult.unavailable(List.of(reason == null ? "rules_only" : reason)),
                null,
                null,
                ruleRiskResult,
                currentEvent,
                summary,
                fusion);
        PersonaAssignment persona = personaRuntimeService.disabled();
        double ruleRiskScore = ruleRiskResult.getRuleRiskScore();
        Map<String, Object> modelScores = buildModelScores(TabularAnomalyResult.unavailable(List.of()), null, null, ruleRiskResult);
        Map<String, Object> modelContributions = buildModelContributions(fusion);
        SessionInsight insight = SessionInsight.builder()
                .insuredId(summary.getInsuredId())
                .sessionId(summary.getSessionId())
                .computedAt(java.time.Instant.now())
                .binaryAnomaly(false)
                .anomaly(ruleRiskScore >= riskProperties.getMediumThreshold())
                .anomalyScore(ruleRiskScore)
                .anomalyProbability(ruleRiskScore / 100.0)
                .binaryDetectorArtifact(reason == null ? "rules_only" : reason)
                .anomalyType(anomalyType.getAnomalyType())
                .anomalyTypeConfidence(anomalyType.getAnomalyTypeConfidence())
                .ensembleRiskScore(ruleRiskScore)
                .riskLevel(fusion.getRiskLevel())
                .contextTags(ruleRiskResult.getTriggeredRules())
                .triggeredRules(ruleRiskResult.getTriggeredRules())
                .warnings(warnings)
                .topContributingFeatures(List.of())
                .explainabilityText(buildEvidenceSummary(null, ruleRiskResult, ruleRiskScore, fusion.getFallbackMode()))
                .sequenceModelPrimary(sequenceProperties.getPrimaryModel())
                .sequenceModelFast(sequenceProperties.getFastModel())
                .aiRiskScore(0.0)
                .ruleRiskScore(ruleRiskScore)
                .ruleContributions(ruleRiskResult.getRuleContributions())
                .ruleEvidence(ruleRiskResult.getRuleEvidence())
                .finalRiskScore(ruleRiskScore)
                .sequenceTopContributions(List.of())
                .anomalyTypeSource(anomalyType.getAnomalyTypeSource())
                .anomalyTypeEvidence(anomalyType.getAnomalyTypeEvidenceJson())
                .personaCluster(persona.getClusterId())
                .personaLabel(persona.getLabel())
                .personaSource(persona.getSource())
                .personaConfidence(persona.getConfidence())
                .personaWarnings(persona.getWarnings())
                .businessContextScore(ruleRiskResult.getBusinessContextScore())
                .aggregationBoost(fusion.getAggregationBoost())
                .riskFusionWeights(fusion.getUsedWeights())
                .unavailableModelWeights(fusion.getUnavailableModelWeights())
                .modelScores(modelScores)
                .modelContributions(modelContributions)
                .fallbackMode(fusion.getFallbackMode())
                .modelArtifacts(Map.of("fusion", "rules_only"))
                .build();
        Map<String, Object> evidencePayload = llmEvidencePayloadService.build(summary, currentEvent, insight);
        return insight.toBuilder()
                .llmExplanationEvidencePayload(evidencePayload)
                .investigationPayload(buildInvestigationPayload(summary, currentEvent, insight, evidencePayload))
                .build();
    }

    private TabularAnomalyResult scoreTabular(SequenceWindowState previousState,
                                              EncodedSequenceEvent currentEncoded,
                                              SequenceModelKind selectedModel,
                                              List<String> warnings) {
        if (selectedModel == SequenceModelKind.RULES_ONLY) {
            return TabularAnomalyResult.unavailable(List.of("load_shedding_rules_only"));
        }
        if (currentEncoded == null) {
            return TabularAnomalyResult.unavailable(List.of("tabular_current_event_missing"));
        }
        if (!currentEncoded.isSchemaValid() && sequenceProperties.isStrictSchema()) {
            return TabularAnomalyResult.unavailable(List.of("tabular_schema_invalid_strict_mode"));
        }
        try {
            TabularAnomalyFeatureVector vector = tabularFeatureService.build(previousState, currentEncoded);
            return tabularAnomalyInferenceService.score(vector);
        } catch (Exception ex) {
            warnings.add("tabular_anomaly_runtime_failed");
            log.warn("Tabular anomaly inference failed for session {}", currentEncoded.getSessionId(), ex);
            return TabularAnomalyResult.unavailable(List.of("tabular_anomaly_runtime_failed"));
        }
    }

    private double aggregationBoost(SessionSummary summary, RuleRiskResult rules) {
        double boost = 0.0;
        List<String> triggered = rules == null ? List.of() : rules.getTriggeredRules();
        if (triggered.size() >= 3) {
            boost += 5.0;
        }
        if (triggered.contains("STATUS_CODE_BURST") && triggered.contains("RAPID_FIRE_EVENTS")) {
            boost += 3.0;
        }
        if (summary != null && defaultInt(summary.getMaxDownloadsIn2Minutes()) >= 10
                && triggered.contains("SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT")) {
            boost += 5.0;
        }
        return Math.min(10.0, boost);
    }

    private Map<String, Object> buildModelScores(TabularAnomalyResult tabular,
                                                 SequenceScoreResult transformer,
                                                 SequenceScoreResult tcn,
                                                 RuleRiskResult rules) {
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("xgboostAnomalyScore", tabular == null ? null : tabular.getXgboostAnomalyScore());
        scores.put("xgboostAnomalyScore100", tabular == null ? null : tabular.getXgboostAnomalyScore100());
        scores.put("lightgbmAlertScore", tabular == null ? null : tabular.getLightgbmAlertScore());
        scores.put("lightgbmAlertScore100", tabular == null ? null : tabular.getLightgbmAlertScore100());
        scores.put("catboostAnomalyScore", tabular == null ? null : tabular.getCatboostAnomalyScore());
        scores.put("catboostAnomalyScore100", tabular == null ? null : tabular.getCatboostAnomalyScore100());
        scores.put("oneClassSvmNoveltyScoreRaw", tabular == null ? null : tabular.getOneClassSvmNoveltyScoreRaw());
        scores.put("oneClassSvmNoveltyScore100", tabular == null ? null : tabular.getOneClassSvmNoveltyScore100());
        scores.put("transformerSurpriseScoreRaw", transformer == null ? null : transformer.getSequenceAnomalyScore());
        scores.put("transformerRiskScore100", transformer == null ? null : transformer.getAiRiskScore());
        scores.put("tcnSurpriseScoreRaw", tcn == null ? null : tcn.getSequenceAnomalyScore());
        scores.put("tcnRiskScore100", tcn == null ? null : tcn.getAiRiskScore());
        scores.put("ruleRiskScore", rules == null ? 0.0 : rules.getRuleRiskScore());
        return scores;
    }

    private Map<String, Object> buildModelContributions(RiskFusionResult fusion) {
        Map<String, Object> contributions = new LinkedHashMap<>();
        contributions.put("xgboost", fusion.getXgboostContribution());
        contributions.put("lightgbm", fusion.getLightgbmContribution());
        contributions.put("transformer", fusion.getTransformerContribution());
        contributions.put("tcn", fusion.getTcnContribution());
        contributions.put("rules", fusion.getRuleContribution());
        contributions.put("businessContext", fusion.getBusinessContextContribution());
        contributions.put("aggregationBoost", fusion.getAggregationBoost());
        return contributions;
    }

    private Map<String, Object> buildForecastContext(ForecastPrediction forecast) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schemaVersion", "v3.6.1");
        if (forecast == null) {
            return context;
        }
        context.put("forecastDate", forecast.getForecastDate());
        context.put("predictedAnomalyRate", forecast.getAnomalyRateForecast());
        context.put("predictedTotalEvents", forecast.getTotalEventsForecast());
        context.put("expectedAlertVolume", forecast.getExpectedAlertVolume());
        context.put("anomalyRateModel", forecast.getAnomalyRateModelName());
        context.put("totalEventsModel", forecast.getTotalEventsModelName());
        context.put("forecastWarnings", forecast.getWarnings());
        return context;
    }

    private Map<String, String> buildArtifactNames(SequenceScoreResult selectedScore,
                                                   TabularAnomalyResult tabular,
                                                   ChurnPrediction churn,
                                                   ForecastPrediction forecast) {
        Map<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put("ranking", safeArtifact(tabular == null ? null : tabular.getXgboostArtifact()));
        artifacts.put("alerting", safeArtifact(tabular == null ? null : tabular.getLightgbmArtifact()));
        artifacts.put("catboost", safeArtifact(tabular == null ? null : tabular.getCatboostArtifact()));
        artifacts.put("oneClassSvm", safeArtifact(tabular == null ? null : tabular.getOneClassSvmArtifact()));
        artifacts.put("sequence", safeArtifact(selectedScore == null ? null : selectedScore.getModelArtifact()));
        artifacts.put("fallback", "tcn_sequence_engine.onnx");
        artifacts.put("churn", safeArtifact(churn == null ? null : churn.getModelArtifact()));
        artifacts.put("forecastTotalEvents", safeArtifact(forecast == null ? null : forecast.getTotalEventsModelArtifact()));
        artifacts.put("forecastAnomalyRate", safeArtifact(forecast == null ? null : forecast.getAnomalyRateModelArtifact()));
        artifacts.put("fusion", "risk_fusion_config.json");
        return artifacts;
    }

    private Map<String, Object> buildInvestigationPayload(SessionSummary summary,
                                                          AuditTrailEvent event,
                                                          SessionInsight insight,
                                                          Map<String, Object> evidencePayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("eventId", event == null ? null : event.getId());
        payload.put("insuredId", summary == null ? null : summary.getInsuredId());
        payload.put("sessionId", summary == null ? null : summary.getSessionId());
        payload.put("riskLevel", insight.getRiskLevel());
        payload.put("finalRiskScore", insight.getFinalRiskScore());
        payload.put("anomalyType", insight.getAnomalyType());
        payload.put("anomalyTypeConfidence", insight.getAnomalyTypeConfidence());
        payload.put("triggeredRules", insight.getTriggeredRules());
        payload.put("modelScores", insight.getModelScores());
        payload.put("modelContributions", insight.getModelContributions());
        payload.put("llmEvidencePayloadAvailable", evidencePayload != null && !evidencePayload.isEmpty());
        return payload;
    }

    private String buildEvidenceSummary(SequenceScoreResult score,
                                        RuleRiskResult rules,
                                        double finalRiskScore,
                                        String fallbackMode) {
        String fields = score == null || score.getTopContributingFields() == null
                ? ""
                : String.join(", ", score.getTopContributingFields().stream().map(item -> item.getField()).limit(3).toList());
        String ruleCodes = rules == null || rules.getTriggeredRules() == null || rules.getTriggeredRules().isEmpty()
                ? "none"
                : String.join(", ", rules.getTriggeredRules());
        return "evidenceSummary: finalRisk=" + String.format(java.util.Locale.ROOT, "%.1f", finalRiskScore)
                + "; fallbackMode=" + fallbackMode
                + "; topSequenceFields=" + fields
                + "; triggeredRules=" + ruleCodes;
    }

    private String safeArtifact(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private AuditTrailEvent latestEvent(List<AuditTrailEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        return events.get(events.size() - 1);
    }

    private List<String> buildContextTags(SessionSummary summary, List<String> triggeredRules, SequenceScoreResult score) {
        List<String> tags = new ArrayList<>();
        if (triggeredRules != null) {
            tags.addAll(triggeredRules);
        }
        if (summary != null && defaultInt(summary.getDeviceChanged()) == 1) {
            tags.add("device_switch");
        }
        if (summary != null && defaultInt(summary.getIpChanged()) == 1) {
            tags.add("country_switch");
        }
        if (score != null && score.isAvailable()) {
            tags.add("sequence_model_" + score.getModelArtifact());
        }
        return tags.stream().distinct().toList();
    }

    private List<FeatureContribution> toFeatureContributions(SequenceScoreResult score) {
        if (score == null || score.getTopContributingFields() == null) {
            return List.of();
        }
        return score.getTopContributingFields().stream()
                .map(item -> FeatureContribution.builder()
                        .feature(item.getField())
                        .importance(item.getContribution())
                        .actualValue(item.getRawValue())
                        .description("Sequence surprise contribution")
                        .build())
                .toList();
    }

    private String buildExplainabilityText(SequenceModelKind modelKind,
                                           SequenceScoreResult score,
                                           List<String> triggeredRules,
                                           double finalRiskScore) {
        String model = score != null && score.getModelArtifact() != null ? score.getModelArtifact() : modelKind.name().toLowerCase();
        String fields = score == null || score.getTopContributingFields() == null
                ? ""
                : String.join(", ", score.getTopContributingFields().stream().map(item -> item.getField()).limit(3).toList());
        String rules = triggeredRules == null || triggeredRules.isEmpty() ? "none" : String.join(", ", triggeredRules);
        return "Model=" + model + "; top surprise fields=" + fields + "; rules=" + rules
                + "; finalRisk=" + String.format(java.util.Locale.ROOT, "%.1f", finalRiskScore);
    }

    private Map<String, Object> buildChurnFeatureMap(SessionSummary summary, List<AuditTrailEvent> events) {
        Map<String, Object> values = new LinkedHashMap<>();
        int total = summary.getTotalEvents() == null ? 0 : summary.getTotalEvents();
        values.put("days_active_ratio", 0.0);
        values.put("avg_session_duration_ms", summary.getTotalDurationSeconds() == null ? 0.0 : summary.getTotalDurationSeconds() * 1000.0);
        values.put("avg_actions_per_session", total);
        values.put("sessions_per_week", 0.0);
        values.put("avg_inter_action_ms", summary.getAvgInterActionSeconds() == null ? 0.0 : summary.getAvgInterActionSeconds() * 1000.0);
        values.put("pages_visited", summary.getUniqueRoutes() == null ? 0 : summary.getUniqueRoutes());
        values.put("failure_rate", total == 0 ? 0.0 : (double) defaultInt(summary.getTotalKOs()) / total);
        values.put("weekend_ratio", defaultInt(summary.getIsWeekend()));
        values.put("business_hours_ratio", businessHoursRatio(events));
        values.put("unique_devices", summary.getUniqueDevicesUsed());
        values.put("unique_countries", summary.getUniqueIpsUsed());
        values.put("fr_events_ratio", frRatio(events));
        values.put("primary_device", primary(events, "device"));
        values.put("primary_browser", primary(events, "browser"));
        values.put("primary_os", primary(events, "os"));
        values.put("primary_region", primary(events, "region"));
        values.put("mobile_ratio", deviceRatio(events, "mobile"));
        values.put("desktop_ratio", deviceRatio(events, "desktop"));
        values.put("tablet_ratio", deviceRatio(events, "tablet"));
        for (String pct : List.of("pct_logging_actions", "pct_contact_actions", "pct_document_actions",
                "pct_banking_actions", "pct_insured_actions", "pct_open_actions")) {
            values.put(pct, actionTypeRatio(events, pct));
        }
        return values;
    }

    private double businessHoursRatio(List<AuditTrailEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0.0;
        }
        long count = events.stream().filter(event -> event.getIsBusinessHours() != null && event.getIsBusinessHours() == 1).count();
        return (double) count / events.size();
    }

    private double frRatio(List<AuditTrailEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0.0;
        }
        long count = events.stream().filter(event -> "fr".equals(TextNormalization.comparisonKey(firstNonBlank(event.getIpCountry(), event.getCountryCode())))).count();
        return (double) count / events.size();
    }

    private double deviceRatio(List<AuditTrailEvent> events, String device) {
        if (events == null || events.isEmpty()) {
            return 0.0;
        }
        long count = events.stream().filter(event -> TextNormalization.comparisonKey(event.getDevice()).contains(device)).count();
        return (double) count / events.size();
    }

    private String primary(List<AuditTrailEvent> events, String field) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AuditTrailEvent event : events) {
            String value = switch (field) {
                case "device" -> event.getDevice();
                case "browser" -> event.getBrowser();
                case "os" -> event.getOs();
                case "region" -> event.getIpRegion();
                default -> null;
            };
            if (value != null && !value.isBlank()) {
                String normalized = TextNormalization.comparisonKey(value).replace(' ', '-');
                counts.put(normalized, counts.getOrDefault(normalized, 0L) + 1);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private double actionTypeRatio(List<AuditTrailEvent> events, String feature) {
        if (events == null || events.isEmpty()) {
            return 0.0;
        }
        String needle = feature.replace("pct_", "").replace("_actions", "");
        long count = events.stream()
                .filter(event -> TextNormalization.comparisonKey(firstNonBlank(event.getActionType(), event.getType())).contains(needle))
                .count();
        return (double) count / events.size();
    }

    private void cacheRuntimeScores(SessionSummary summary, SessionInsight insight) {
        Map<String, Object> scorePayload = new LinkedHashMap<>();
        scorePayload.put("schemaVersion", "v3.6.1");
        scorePayload.put("computedAt", insight.getComputedAt());
        scorePayload.put("selectedSequenceModel", insight.getSelectedSequenceModel());
        scorePayload.put("sequenceAnomalyScore", valueOrZero(insight.getSequenceAnomalyScore()));
        scorePayload.put("transformerRiskScore100", insight.getTransformerRiskScore100());
        scorePayload.put("tcnRiskScore100", insight.getTcnRiskScore100());
        scorePayload.put("xgboostAnomalyScore100", insight.getXgboostAnomalyScore100());
        scorePayload.put("lightgbmAlertScore100", insight.getLightgbmAlertScore100());
        scorePayload.put("aiRiskScore", valueOrZero(insight.getAiRiskScore()));
        scorePayload.put("ruleRiskScore", valueOrZero(insight.getRuleRiskScore()));
        scorePayload.put("finalRiskScore", valueOrZero(insight.getFinalRiskScore()));
        scorePayload.put("fallbackMode", insight.getFallbackMode());
        redisCacheService.addToJsonList(CacheKeys.sequenceScoresKey(summary.getSessionId()), scorePayload, redisCacheProperties.getSessionBuffer());

        Map<String, Object> riskPayload = new LinkedHashMap<>();
        riskPayload.put("schemaVersion", "v3.6.1");
        riskPayload.put("finalRiskScore", valueOrZero(insight.getFinalRiskScore()));
        riskPayload.put("riskLevel", insight.getRiskLevel());
        riskPayload.put("anomalyType", insight.getAnomalyType());
        riskPayload.put("modelScores", insight.getModelScores());
        riskPayload.put("modelContributions", insight.getModelContributions());
        riskPayload.put("updatedAt", insight.getComputedAt());
        redisCacheService.setJson(CacheKeys.sessionRiskV36Key(summary.getInsuredId(), summary.getSessionId()), riskPayload, redisCacheProperties.getSessionInsight());

        Object eventId = insight.getLlmExplanationEvidencePayload() == null ? null : insight.getLlmExplanationEvidencePayload().get("eventId");
        if (eventId != null && !String.valueOf(eventId).isBlank()) {
            redisCacheService.setJson(CacheKeys.alertLlmEvidenceKey(String.valueOf(eventId)),
                    insight.getLlmExplanationEvidencePayload(),
                    redisCacheProperties.getSessionInsight());
            redisCacheService.setJson(CacheKeys.alertInvestigationKey(String.valueOf(eventId)),
                    insight.getInvestigationPayload(),
                    redisCacheProperties.getSessionInsight());
        }
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private String resolveRiskLevel(double riskScore) {
        if (riskScore >= riskProperties.getCriticalThreshold()) {
            return "CRITICAL";
        }
        if (riskScore >= riskProperties.getHighThreshold()) {
            return "HIGH";
        }
        if (riskScore >= riskProperties.getMediumThreshold()) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }
}
