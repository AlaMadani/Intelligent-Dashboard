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
import com.noveocare.dataprocessor.config.AiDiagnosticsProperties;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.config.AiRiskScoringProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import com.noveocare.dataprocessor.config.AiChurnProperties;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
public class ModelInferenceService {

    private final AiSequenceProperties sequenceProperties;
    private final AiRiskScoringProperties riskProperties;
    private final AiDiagnosticsProperties diagnosticsProperties;
    private final AiTabularAnomalyProperties tabularProperties;
    private final AiChurnProperties churnProperties;
    private final AiForecastProperties forecastProperties;
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
    private final InferenceConfig inferenceConfig;
    private final InferenceExecutorManager executorManager;

    private final AtomicLong artifactLoadCount = new AtomicLong();
    private final Map<String, AtomicLong> modelArtifactLoadCountByModel = new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicLong optionalModelsSkippedTotal = new AtomicLong();
    private String lastLiveFastModeSkipReason;
    private final ThreadLocal<Map<String, Long>> lastTimingBreakdown = ThreadLocal.withInitial(LinkedHashMap::new);
    private boolean sequenceModelSelectionLogged = false;

    @jakarta.annotation.PostConstruct
    public void logSequenceModelSelection() {
        if (sequenceModelSelectionLogged) return;
        sequenceModelSelectionLogged = true;
        String mode;
        boolean tEnabled = sequenceProperties.isTransformerEnabled();
        boolean cEnabled = sequenceProperties.isTcnEnabled();
        boolean both = sequenceProperties.isRunBoth();
        if (!sequenceProperties.isEnabled()) {
            mode = "DISABLED";
        } else if (!tEnabled && !cEnabled) {
            mode = "DISABLED";
        } else if (both && tEnabled && cEnabled) {
            mode = "BOTH_SELECT_MAX";
        } else if (tEnabled && !cEnabled) {
            mode = "TRANSFORMER_ONLY";
        } else if (!tEnabled && cEnabled) {
            mode = "TCN_ONLY";
        } else {
            mode = "LOAD_SHEDDING_SELECTS";
        }
        log.info("SEQUENCE_MODEL_SELECTION mode={} transformerEnabled={} tcnEnabled={} runBoth={} primaryModel={} fastModel={} loadSheddingModel={} selected={}",
                mode, tEnabled, cEnabled, both, sequenceProperties.getPrimaryModel(), sequenceProperties.getFastModel(), sequenceProperties.getLoadSheddingModel(), mode);
    }

    public static class InferenceTiming {
        public long totalInferenceMs;
        public long tabularInferenceMs;
        public long xgboostMs;
        public long lightgbmMs;
        public long catboostMs;
        public long oneClassSvmMs;
        public long sequenceInferenceMs;
        public long transformerMs;
        public long tcnMs;
        public long churnMs;
        public long forecastMs;
    }

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

        if (!inferenceConfig.isInferenceEnabled()) {
            warnings.add("ai_inference_disabled");
            return rulesOnlyInsight(summary, currentEvent, triggeredRules, warnings, "ai_inference_disabled");
        }

        InferenceTiming timing = new InferenceTiming();
        long inferenceStart = System.currentTimeMillis();

        long windowFetchStart = System.nanoTime();
        SequenceWindowState previousState = windowService.load(summary.getSessionId());
        long windowFetchMs = (System.nanoTime() - windowFetchStart) / 1_000_000L;

        long inputBuildStart = System.nanoTime();
        EncodedSequenceEvent currentEncoded = preprocessingService.encode(currentEvent, windowService.latestTimestamp(previousState));
        warnings.addAll(currentEncoded.getWarnings() == null ? List.of() : currentEncoded.getWarnings());

        RuleRiskResult ruleRiskResult = ruleRiskScoringService.evaluate(summary, enrichedEvents, triggeredRules);
        double ruleRiskScore = ruleRiskResult.getRuleRiskScore();
        SequenceModelKind selectedModel = loadSheddingService.selectModel(kafkaLag);
        SequenceScoreResult selectedScore = SequenceScoreResult.unavailable(List.of());
        SequenceScoreResult transformerScoreResult = null;
        SequenceScoreResult tcnScoreResult = null;
        String fallbackMode = "normal";

        long liveFastBudget = inferenceConfig.getLiveFastModeBudgetMs();
        boolean liveFast = inferenceConfig.isLiveFastModeEnabled();
        boolean liveFastExhausted = false;

        try {
            if (!sequenceProperties.isEnabled()) {
                warnings.add("sequence_inference_disabled_live_fast_mode");
                fallbackMode = "sequence_disabled_live_fast_mode";
            } else if (!currentEncoded.isSchemaValid()) {
                warnings.add("sequence_schema_invalid");
                fallbackMode = "schema_invalid_rules_only";
            } else if (selectedModel == SequenceModelKind.RULES_ONLY) {
                warnings.add("load_shedding_rules_only");
                fallbackMode = "rules_only";
            } else if (previousState.realEventCount() < sequenceProperties.getMinContextEvents()) {
                warnings.add("insufficient_sequence_context");
                fallbackMode = "insufficient_context";
            } else if (liveFast && inferenceConfig.isLiveFastModeSkipSequenceUnderLoad()
                    && executorManager.getQueueSize("sequence") > 0) {
                warnings.add("live_fast_skip_sequence_under_load");
                fallbackMode = "live_fast_skip_sequence";
                liveFastExhausted = true;
            } else {
                long seqStart = System.currentTimeMillis();
                SequenceWindow previousWindow = windowService.toWindow(previousState);
                long inputBuildMs = (System.nanoTime() - inputBuildStart) / 1_000_000L;

                int recentEventsSize = previousState == null ? 0 : (previousState.getEvents() == null ? 0 : previousState.getEvents().size());
                long[][][] liveXCat = previousWindow == null ? null : previousWindow.getXCat();
                float[][][] liveXCont = previousWindow == null ? null : previousWindow.getXCont();
                int windowSize = liveXCat == null ? 0 : liveXCat[0].length;
                int catCount = (liveXCat == null || liveXCat[0].length == 0) ? 0 : liveXCat[0][0].length;
                int contCount = (liveXCont == null || liveXCont[0].length == 0) ? 0 : liveXCont[0][0].length;

                log.info("SEQUENCE_LIVE_INPUT model={} eventId={} insuredId={} sessionId={}"
                                + " x_cat_shape=[{},{},{}] x_cont_shape=[{},{},{}] mask_shape=[{},{}]"
                                + " recentEventsSize={} sequenceWindowSize={}"
                                + " enabled={} transformerEnabled={} tcnEnabled={}",
                        selectedModel.name().toLowerCase(), currentEvent.getId(),
                        summary.getInsuredId(), summary.getSessionId(),
                        1, windowSize, catCount,
                        1, windowSize, contCount,
                        1, windowSize,
                        recentEventsSize, windowSize,
                        sequenceProperties.isEnabled(), sequenceProperties.isTransformerEnabled(),
                        sequenceProperties.isTcnEnabled());

                boolean transformerActuallyEnabled = inferenceConfig.isTransformerEnabled();
                boolean tcnActuallyEnabled = inferenceConfig.isTcnEnabled();
                if (!transformerActuallyEnabled && selectedModel == SequenceModelKind.TRANSFORMER) {
                    selectedModel = tcnActuallyEnabled ? SequenceModelKind.TCN : SequenceModelKind.RULES_ONLY;
                    warnings.add(selectedModel == SequenceModelKind.TCN
                            ? "transformer_disabled_fallback_tcn" : "sequence_models_disabled");
                }
                if (!tcnActuallyEnabled && selectedModel == SequenceModelKind.TCN) {
                    selectedModel = transformerActuallyEnabled ? SequenceModelKind.TRANSFORMER : SequenceModelKind.RULES_ONLY;
                    warnings.add(selectedModel == SequenceModelKind.TRANSFORMER
                            ? "tcn_disabled_fallback_transformer" : "sequence_models_disabled");
                }
                if (liveFast && inferenceConfig.isLiveFastModeSkipTransformer()
                        && selectedModel == SequenceModelKind.TRANSFORMER) {
                    warnings.add("live_fast_mode_skipped_transformer");
                    if (inferenceConfig.isLiveFastModeAllowTcn() && tcnActuallyEnabled) {
                        selectedModel = SequenceModelKind.TCN;
                    } else {
                        fallbackMode = "live_fast_skip_transformer";
                        selectedScore = SequenceScoreResult.unavailable(List.of("live_fast_mode_skipped_transformer"));
                    }
                }
                if (selectedModel == SequenceModelKind.RULES_ONLY) {
                    if (!"live_fast_skip_transformer".equals(fallbackMode)) {
                        fallbackMode = "sequence_disabled_no_model";
                    }
                    if (selectedScore == null) {
                        selectedScore = SequenceScoreResult.unavailable(List.of(fallbackMode));
                    }
                } else if (sequenceProperties.isRunBoth() && selectedModel == SequenceModelKind.TRANSFORMER
                        && transformerActuallyEnabled && tcnActuallyEnabled) {
                    SequenceScoreResult[] tRef = new SequenceScoreResult[1];
                    SequenceScoreResult[] tcnRef = new SequenceScoreResult[1];
                    timing.transformerMs = runTimedSequenceModel("transformer",
                            SequenceModelKind.TRANSFORMER, previousWindow, currentEncoded, tRef,
                            currentEvent.getId(), summary.getInsuredId(), summary.getSessionId(),
                            windowFetchMs, inputBuildMs);
                    if (timing.transformerMs >= 0) {
                        timing.tcnMs = runTimedSequenceModel("tcn",
                                SequenceModelKind.TCN, previousWindow, currentEncoded, tcnRef,
                                currentEvent.getId(), summary.getInsuredId(), summary.getSessionId(),
                                windowFetchMs, inputBuildMs);
                    } else {
                        timing.tcnMs = -1;
                    }
                    transformerScoreResult = tRef[0];
                    tcnScoreResult = tcnRef[0];
                    selectedScore = chooseMaxRisk(transformerScoreResult, tcnScoreResult);
                } else {
                    if (selectedModel == SequenceModelKind.TCN && tcnActuallyEnabled) {
                        fallbackMode = "tcn";
                        SequenceScoreResult[] tcnRef = new SequenceScoreResult[1];
                        timing.tcnMs = runTimedSequenceModel("tcn",
                                SequenceModelKind.TCN, previousWindow, currentEncoded, tcnRef,
                                currentEvent.getId(), summary.getInsuredId(), summary.getSessionId(),
                                windowFetchMs, inputBuildMs);
                        tcnScoreResult = tcnRef[0];
                        selectedScore = tcnScoreResult;
                    } else if (selectedModel == SequenceModelKind.TRANSFORMER && transformerActuallyEnabled) {
                        fallbackMode = "transformer";
                        SequenceScoreResult[] tRef = new SequenceScoreResult[1];
                        timing.transformerMs = runTimedSequenceModel("transformer",
                                SequenceModelKind.TRANSFORMER, previousWindow, currentEncoded, tRef,
                                currentEvent.getId(), summary.getInsuredId(), summary.getSessionId(),
                                windowFetchMs, inputBuildMs);
                        transformerScoreResult = tRef[0];
                        selectedScore = transformerScoreResult;
                    } else {
                        fallbackMode = "sequence_disabled";
                        if (selectedScore == null) {
                            selectedScore = SequenceScoreResult.unavailable(List.of("sequence_model_unavailable"));
                        }
                    }
                }
                timing.sequenceInferenceMs = System.currentTimeMillis() - seqStart;
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
            if (sequenceProperties.isEnabled() && (currentEncoded.isSchemaValid() || !sequenceProperties.isStrictSchema())) {
                windowService.appendAndSave(summary.getSessionId(), currentEncoded);
            }
        }

        if (selectedScore == null) {
            selectedScore = SequenceScoreResult.unavailable(List.of("sequence_unavailable"));
        }
        warnings.addAll(selectedScore.getWarnings() == null ? List.of() : selectedScore.getWarnings());

        boolean sequenceRunBoth = sequenceProperties.isRunBoth();
        List<String> sequenceActuallyRanModels = new ArrayList<>();
        if (transformerScoreResult != null && transformerScoreResult.isAvailable()) {
            sequenceActuallyRanModels.add("transformer");
        }
        if (tcnScoreResult != null && tcnScoreResult.isAvailable()) {
            sequenceActuallyRanModels.add("tcn");
        }
        boolean transformerUsedInFusion = transformerScoreResult != null && transformerScoreResult.isAvailable();
        boolean tcnUsedInFusion = tcnScoreResult != null && tcnScoreResult.isAvailable();

        TabularAnomalyResult tabularResult;
        long tabularStart = System.currentTimeMillis();
        if (!tabularProperties.isEnabled()) {
            tabularResult = TabularAnomalyResult.unavailable(List.of("tabular_inference_disabled"));
            warnings.add("tabular_inference_disabled");
        } else if (liveFast && liveFastExhausted) {
            tabularResult = TabularAnomalyResult.unavailable(List.of("live_fast_mode_skip_tabular"));
            warnings.add("live_fast_mode_skip_tabular");
            optionalModelsSkippedTotal.incrementAndGet();
            lastLiveFastModeSkipReason = "live_fast_skip_tabular";
        } else {
            tabularResult = scoreTabular(previousState, currentEncoded, selectedModel, warnings, timing);
        }
        timing.tabularInferenceMs = System.currentTimeMillis() - tabularStart;
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

        long churnStart = System.currentTimeMillis();
        ChurnPrediction churn;
        if (!churnProperties.isEnabled()) {
            churn = ChurnPrediction.builder().available(false).warnings(List.of("churn_inference_disabled")).build();
            warnings.add("churn_inference_disabled");
        } else if (liveFast && liveFastExhausted) {
            churn = ChurnPrediction.builder().available(false).warnings(List.of("live_fast_mode_skip_churn")).build();
            warnings.add("live_fast_mode_skip_churn");
            optionalModelsSkippedTotal.incrementAndGet();
            lastLiveFastModeSkipReason = "live_fast_skip_churn";
        } else {
            churn = runTimedChurn(summary, enrichedEvents, fusion, timing);
        }
        timing.churnMs = System.currentTimeMillis() - churnStart;
        recordChurnRuntimeHealth(churn);
        warnings.addAll(churn.getWarnings() == null ? List.of() : churn.getWarnings());

        long forecastStart = System.currentTimeMillis();
        ForecastPrediction forecast;
        if (!forecastProperties.isEnabled() || !forecastProperties.isRunInListener()) {
            forecast = ForecastPrediction.builder().warnings(List.of("forecast_not_run_in_listener")).build();
        } else if (liveFast && liveFastExhausted) {
            forecast = ForecastPrediction.builder().warnings(List.of("live_fast_mode_skip_forecast")).build();
            warnings.add("live_fast_mode_skip_forecast");
            optionalModelsSkippedTotal.incrementAndGet();
            lastLiveFastModeSkipReason = "live_fast_skip_forecast";
        } else {
            forecast = forecastRuntimeService.forecast(LocalDate.now(ZoneOffset.UTC));
        }
        timing.forecastMs = System.currentTimeMillis() - forecastStart;
        recordForecastRuntimeHealth(forecast);
        warnings.addAll(forecast.getWarnings());
        modelHealthService.publish();

        timing.totalInferenceMs = System.currentTimeMillis() - inferenceStart;

        if (timing.totalInferenceMs > 2000) {
            log.warn("Total inference slow: {}ms for session {}/{}, tabular={}ms seq={}ms churn={}ms forecast={}ms",
                    timing.totalInferenceMs, summary.getInsuredId(), summary.getSessionId(),
                    timing.tabularInferenceMs, timing.sequenceInferenceMs, timing.churnMs, timing.forecastMs);
        }

        if (diagnosticsProperties.isTraceRiskBreakdown()) {
            log.info("RISK_BREAKDOWN insuredId={} sessionId={} eventId={} stage=LIVE"
                            + " xgboostRaw={} xgboostScore100={} lightgbmRaw={} lightgbmScore100={}"
                            + " catboostRaw={} catboostScore100={} oneClassSvmRaw={} oneClassSvmScore100={}"
                            + " transformerRaw={} transformerScore100={} tcnRaw={} tcnScore100={}"
                            + " sequenceSelectedModel={} sequenceScore100={}"
                            + " sequenceRunBoth={} sequenceActuallyRanModels={}"
                            + " transformerUsedInFusion={} tcnUsedInFusion={}"
                            + " ruleRiskScore={} businessContextScore={} aggregationBoost={}"
                            + " churnRaw={} churnScore100={} churnUsedInFusion=false"
                            + " forecastRaw={} forecastScore100={} forecastUsedInFusion=false"
                            + " xgbContribution={} lgbmContribution={} transformerContribution={}"
                            + " tcnContribution={} ruleContribution={} businessContribution={}"
                            + " finalRisk={} riskScale=ZERO_TO_ONE_HUNDRED riskTier={}"
                            + " triggeredRules={}",
                    summary.getInsuredId(), summary.getSessionId(), currentEvent.getId(),
                    tabularResult == null ? null : tabularResult.getXgboostAnomalyScore(),
                    tabularResult == null ? null : tabularResult.getXgboostAnomalyScore100(),
                    tabularResult == null ? null : tabularResult.getLightgbmAlertScore(),
                    tabularResult == null ? null : tabularResult.getLightgbmAlertScore100(),
                    tabularResult == null ? null : tabularResult.getCatboostAnomalyScore(),
                    tabularResult == null ? null : tabularResult.getCatboostAnomalyScore100(),
                    tabularResult == null ? null : tabularResult.getOneClassSvmNoveltyScoreRaw(),
                    tabularResult == null ? null : tabularResult.getOneClassSvmNoveltyScore100(),
                    transformerScoreResult == null ? null : transformerScoreResult.getSequenceAnomalyScore(),
                    transformerScoreResult == null ? null : transformerScoreResult.getAiRiskScore(),
                    tcnScoreResult == null ? null : tcnScoreResult.getSequenceAnomalyScore(),
                    tcnScoreResult == null ? null : tcnScoreResult.getAiRiskScore(),
                    selectedScore == null ? null : selectedScore.getModelKind(),
                    selectedScore == null ? null : selectedScore.getAiRiskScore(),
                    sequenceRunBoth, sequenceActuallyRanModels,
                    transformerUsedInFusion, tcnUsedInFusion,
                    ruleRiskResult == null ? null : ruleRiskResult.getRuleRiskScore(),
                    ruleRiskResult == null ? null : ruleRiskResult.getBusinessContextScore(),
                    fusion.getAggregationBoost(),
                    churn == null ? null : churn.getProbability(),
                    churn == null ? null : (churn.getProbability() != null ? churn.getProbability() * 100.0 : null),
                    forecast == null ? null : forecast.getAnomalyRateForecast(),
                    forecast == null ? null : forecast.getTotalEventsForecast(),
                    fusion.getXgboostContribution(), fusion.getLightgbmContribution(),
                    fusion.getTransformerContribution(), fusion.getTcnContribution(),
                    fusion.getRuleContribution(), fusion.getBusinessContextContribution(),
                    fusion.getFinalRiskScore(), fusion.getRiskLevel(),
                    ruleRiskResult == null ? null : ruleRiskResult.getTriggeredRules());
        }

        Map<String, Long> timingBreakdown = lastTimingBreakdown.get();
        timingBreakdown.clear();
        timingBreakdown.put("inferenceMs", timing.totalInferenceMs);
        timingBreakdown.put("tabularInferenceMs", timing.tabularInferenceMs);
        timingBreakdown.put("xgboostMs", timing.xgboostMs);
        timingBreakdown.put("lightgbmMs", timing.lightgbmMs);
        timingBreakdown.put("catboostMs", timing.catboostMs);
        timingBreakdown.put("oneClassSvmMs", timing.oneClassSvmMs);
        timingBreakdown.put("sequenceInferenceMs", timing.sequenceInferenceMs);
        timingBreakdown.put("transformerMs", timing.transformerMs);
        timingBreakdown.put("tcnMs", timing.tcnMs);
        timingBreakdown.put("churnMs", timing.churnMs);
        timingBreakdown.put("forecastMs", timing.forecastMs);

        double aiRiskScore = Math.max(
                valueOrZero(tabularResult.getXgboostAnomalyScore100()),
                Math.max(valueOrZero(tabularResult.getLightgbmAlertScore100()), valueOrZero(selectedScore.getAiRiskScore())));
        double finalRiskScore = fusion.getFinalRiskScore();
        Map<String, Object> modelScores = buildModelScores(tabularResult, transformerScoreResult, tcnScoreResult, ruleRiskResult);
        addSequenceTrackingToModelScores(modelScores, sequenceRunBoth, sequenceActuallyRanModels, transformerUsedInFusion, tcnUsedInFusion);
        Map<String, Object> modelContributions = buildModelContributions(fusion);
        Map<String, Object> forecastContext = buildForecastContext(forecast);
        warnings.add("anomaly_probability_is_final_risk_normalized_not_sequence_probability");

        if (liveFast && liveFastExhausted) {
            warnings.add("live_fast_mode_skipped_optional_models");
        }

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
                .riskScale(fusion.getRiskScale())
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
                .sequenceRunBoth(sequenceRunBoth)
                .sequenceActuallyRanModels(sequenceActuallyRanModels)
                .transformerUsedInFusion(transformerUsedInFusion)
                .tcnUsedInFusion(tcnUsedInFusion)
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

    public InferenceTimingRecord getLastTiming() {
        return null;
    }

    public static class InferenceTimingRecord {
        public final long totalInferenceMs;
        public final long tabularInferenceMs;
        public final long xgboostMs;
        public final long lightgbmMs;
        public final long catboostMs;
        public final long oneClassSvmMs;
        public final long sequenceInferenceMs;
        public final long transformerMs;
        public final long tcnMs;
        public final long churnMs;
        public final long forecastMs;

        public InferenceTimingRecord(long totalInferenceMs, long tabularInferenceMs,
                                     long xgboostMs, long lightgbmMs, long catboostMs, long oneClassSvmMs,
                                     long sequenceInferenceMs, long transformerMs, long tcnMs,
                                     long churnMs, long forecastMs) {
            this.totalInferenceMs = totalInferenceMs;
            this.tabularInferenceMs = tabularInferenceMs;
            this.xgboostMs = xgboostMs;
            this.lightgbmMs = lightgbmMs;
            this.catboostMs = catboostMs;
            this.oneClassSvmMs = oneClassSvmMs;
            this.sequenceInferenceMs = sequenceInferenceMs;
            this.transformerMs = transformerMs;
            this.tcnMs = tcnMs;
            this.churnMs = churnMs;
            this.forecastMs = forecastMs;
        }
    }

    public SessionInsight inferLightweight(SessionSummary summary,
                                           List<AuditTrailEvent> enrichedEvents,
                                           List<String> triggeredRules,
                                           String reason) {
        List<String> warnings = new ArrayList<>();
        warnings.add(reason == null || reason.isBlank() ? "rules_only" : reason);
        return rulesOnlyInsight(summary, latestEvent(enrichedEvents), triggeredRules, warnings, reason);
    }

    private long runTimedModelSync(String modelName, long timeoutMsOverride, Runnable task) {
        if (executorManager.isCircuitOpen(modelName)) {
            log.debug("Circuit open for model {}, skipping", modelName);
            modelHealthService.recordRuntimeError(modelName, "circuit_open");
            return -1;
        }
        long timeoutMs = timeoutMsOverride > 0 ? timeoutMsOverride : inferenceConfig.getTimeoutMs(modelName);
        long start = System.nanoTime();
        String group = modelName.equals("transformer") || modelName.equals("tcn") ? "sequence"
                : modelName.equals("churn") ? "churn" : "tabular";
        Object result = executorManager.submitWithTimeout(group, modelName, () -> {
            task.run();
            return true;
        }, timeoutMs, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        if (result == null) {
            log.warn("Model {} timed out/rejected after {}ms", modelName, elapsedMs);
            modelHealthService.recordRuntimeError(modelName, "model_timeout:" + modelName);
            executorManager.checkAndOpenCircuit(modelName,
                    inferenceConfig.getCircuitBreakerTimeoutThreshold(),
                    inferenceConfig.getCircuitBreakerCooldownMs());
            if (inferenceConfig.isDisableModelForRunAfterCircuitOpen()) {
                executorManager.disablePermanently(modelName);
            }
            return -1;
        }
        if (elapsedMs > timeoutMs) {
            log.warn("Model {} slower than timeout: {}ms > {}ms", modelName, elapsedMs, timeoutMs);
        }
        return elapsedMs;
    }

    private long runTimedSequenceModel(String modelName, SequenceModelKind kind, SequenceWindow window,
                                       EncodedSequenceEvent target, SequenceScoreResult[] outResult,
                                       String eventId, String insuredId, String sessionId,
                                       long windowFetchMs, long inputBuildMs) {
        long seqTotalStart = System.nanoTime();
        String status = "OK";
        String errorClass = "";
        String errorMessage = "";

        if (sequenceProperties.isDebugDirectRun()) {
            log.info("SEQUENCE_DIRECT_RUN_ENABLED model={} sessionId={}", modelName, sessionId);
            try {
                long infT0 = System.nanoTime();
                SequenceInferenceResult inference = onnxInferenceService.infer(kind, window);
                long inferenceNs = System.nanoTime() - infT0;
                long postprocessStart = System.nanoTime();
                SequenceScoreResult score = scoringService.score(inference, target);
                long postprocessNs = System.nanoTime() - postprocessStart;
                long totalMs = (System.nanoTime() - seqTotalStart) / 1_000_000L;

                outResult[0] = score;
                log.info("SEQUENCE_LIVE_TIMING model={} eventId={} sessionId={}"
                                + " windowFetchMs={} inputBuildMs={} tensorCreateMs={} sessionRunMs={}"
                                + " outputExtractMs={} postprocessMs={} totalMs={}"
                                + " status={} errorClass={} errorMessage={}",
                        modelName, eventId, sessionId,
                        windowFetchMs, inputBuildMs,
                        score.getLatencyMillis(), score.getLatencyMillis(),
                        0L, postprocessNs / 1_000_000L, totalMs,
                        "OK", "", "");
                return totalMs;
            } catch (Exception e) {
                status = "FAILED";
                errorClass = e.getClass().getSimpleName();
                errorMessage = e.getMessage() != null ? e.getMessage() : "";
                long totalMs = (System.nanoTime() - seqTotalStart) / 1_000_000L;
                log.warn("SEQUENCE_LIVE_TIMING model={} eventId={} sessionId={}"
                                + " windowFetchMs={} inputBuildMs={} totalMs={}"
                                + " status={} errorClass={} errorMessage=\"{}\"",
                        modelName, eventId, sessionId,
                        windowFetchMs, inputBuildMs, totalMs,
                        status, errorClass, errorMessage);
                outResult[0] = SequenceScoreResult.unavailable(List.of(modelName + "_direct_failed"));
                return -1;
            }
        }

        if (executorManager.isCircuitOpen(modelName)) {
            log.debug("Circuit open for sequence model {}, skipping", modelName);
            outResult[0] = SequenceScoreResult.unavailable(List.of(modelName + "_circuit_open"));
            return -1;
        }
        long timeoutMs = inferenceConfig.getTimeoutMs(modelName);
        long submitStart = System.nanoTime();
        SequenceScoreResult result = executorManager.submitWithTimeout("sequence", modelName, () -> {
            SequenceInferenceResult inference = onnxInferenceService.infer(kind, window);
            return scoringService.score(inference, target);
        }, timeoutMs, SequenceScoreResult.unavailable(List.of(modelName + "_timeout")));
        long totalMs = (System.nanoTime() - submitStart) / 1_000_000L;
        outResult[0] = result;
        boolean timedOut = result == null || !result.isAvailable() || totalMs >= timeoutMs;
        if (timedOut) {
            status = "TIMEOUT";
            long elapsedMs = (System.nanoTime() - seqTotalStart) / 1_000_000L;
            log.warn("SEQUENCE_LIVE_TIMING model={} eventId={} sessionId={}"
                            + " windowFetchMs={} inputBuildMs={} totalMs={}"
                            + " status=TIMEOUT timeoutMs={}",
                    modelName, eventId, sessionId,
                    windowFetchMs, inputBuildMs, elapsedMs, timeoutMs);
            executorManager.checkAndOpenCircuit(modelName,
                    inferenceConfig.getCircuitBreakerTimeoutThreshold(),
                    inferenceConfig.getCircuitBreakerCooldownMs());
            if (inferenceConfig.isDisableModelForRunAfterCircuitOpen()) {
                executorManager.disablePermanently(modelName);
            }
            return -1;
        }
        log.info("SEQUENCE_LIVE_TIMING model={} eventId={} sessionId={}"
                        + " windowFetchMs={} inputBuildMs={} totalMs={}"
                        + " status={} errorClass={} errorMessage={}",
                modelName, eventId, sessionId,
                windowFetchMs, inputBuildMs, totalMs,
                "OK", "", "");
        return totalMs;
    }

    private SequenceScoreResult runAndScore(SequenceModelKind modelKind, SequenceWindow previousWindow, EncodedSequenceEvent target) {
        SequenceInferenceResult inference = onnxInferenceService.infer(modelKind, previousWindow);
        return scoringService.score(inference, target);
    }

    private SequenceScoreResult chooseMaxRisk(SequenceScoreResult left, SequenceScoreResult right) {
        double leftRisk = left == null || left.getAiRiskScore() == null ? 0.0 : left.getAiRiskScore();
        double rightRisk = right == null || right.getAiRiskScore() == null ? 0.0 : right.getAiRiskScore();
        return leftRisk >= rightRisk ? left : right;
    }

    private ChurnPrediction runTimedChurn(SessionSummary summary, List<AuditTrailEvent> enrichedEvents,
                                           RiskFusionResult fusion, InferenceTiming timing) {
        long start = System.nanoTime();
        boolean isHighRisk = fusion.getFinalRiskScore() >= riskProperties.getMediumThreshold();
        if (!churnProperties.isEnabled()) {
            return ChurnPrediction.builder().available(false).warnings(List.of("churn_inference_disabled")).build();
        }
        ChurnPrediction result = churnInferenceService.predict(summary, enrichedEvents, isHighRisk);
        timing.churnMs = (System.nanoTime() - start) / 1_000_000L;
        return result;
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
                                              List<String> warnings,
                                              InferenceTiming timing) {
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
            TabularAnomalyResult result = tabularAnomalyInferenceService.score(vector);
            Map<String, Long> latencies = result.getLatencyByModel();
            if (latencies != null) {
                timing.xgboostMs = latencies.getOrDefault("xgboost", 0L);
                timing.lightgbmMs = latencies.getOrDefault("lightgbm", 0L);
                timing.catboostMs = latencies.getOrDefault("catboost", 0L);
                timing.oneClassSvmMs = latencies.getOrDefault("oneclasssvm", 0L);
            }
            return result;
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

    private void addSequenceTrackingToModelScores(Map<String, Object> scores,
                                                   boolean sequenceRunBoth,
                                                   List<String> sequenceActuallyRanModels,
                                                   boolean transformerUsedInFusion,
                                                   boolean tcnUsedInFusion) {
        scores.put("sequenceRunBoth", sequenceRunBoth);
        scores.put("sequenceActuallyRanModels", sequenceActuallyRanModels);
        scores.put("transformerUsedInFusion", transformerUsedInFusion);
        scores.put("tcnUsedInFusion", tcnUsedInFusion);
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
        payload.put("sequenceRunBoth", insight.getSequenceRunBoth());
        payload.put("sequenceSelectedModel", insight.getSelectedSequenceModel());
        payload.put("sequenceActuallyRanModels", insight.getSequenceActuallyRanModels());
        payload.put("transformerUsedInFusion", insight.getTransformerUsedInFusion());
        payload.put("tcnUsedInFusion", insight.getTcnUsedInFusion());
        payload.put("modelScores", insight.getModelScores());
        payload.put("modelContributions", insight.getModelContributions());
        payload.put("llmEvidencePayloadAvailable", evidencePayload != null && !evidencePayload.isEmpty());
        payload.put("sessionEndReason", null);
        payload.put("sessionEndedExplicitly", false);
        payload.put("sessionEndedAt", null);
        payload.put("sessionDurationMs", summary == null || summary.getTotalDurationSeconds() == null ? null : summary.getTotalDurationSeconds() * 1000L);
        payload.put("sessionEventCount", summary == null ? null : summary.getTotalEvents());
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
        if (events == null || events.isEmpty()) return 0.0;
        long count = events.stream().filter(event -> event.getIsBusinessHours() != null && event.getIsBusinessHours() == 1).count();
        return (double) count / events.size();
    }

    private double frRatio(List<AuditTrailEvent> events) {
        if (events == null || events.isEmpty()) return 0.0;
        long count = events.stream().filter(event -> "fr".equals(TextNormalization.comparisonKey(firstNonBlank(event.getIpCountry(), event.getCountryCode())))).count();
        return (double) count / events.size();
    }

    private double deviceRatio(List<AuditTrailEvent> events, String device) {
        if (events == null || events.isEmpty()) return 0.0;
        long count = events.stream().filter(event -> TextNormalization.comparisonKey(event.getDevice()).contains(device)).count();
        return (double) count / events.size();
    }

    private String primary(List<AuditTrailEvent> events, String field) {
        if (events == null || events.isEmpty()) return null;
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
        if (events == null || events.isEmpty()) return 0.0;
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
        scorePayload.put("sequenceRunBoth", insight.getSequenceRunBoth());
        scorePayload.put("sequenceActuallyRanModels", insight.getSequenceActuallyRanModels());
        scorePayload.put("transformerUsedInFusion", insight.getTransformerUsedInFusion());
        scorePayload.put("tcnUsedInFusion", insight.getTcnUsedInFusion());
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
            Map<String, Object> ev = insight.getLlmExplanationEvidencePayload();
            log.info("LLM_EVIDENCE_PAYLOAD_WRITE eventId={} insuredId={} sessionId={} hash={} version={}",
                    eventId, summary.getInsuredId(), summary.getSessionId(),
                    ev.get("evidenceHash"), ev.get("evidenceVersion"));
        }
    }

    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private String resolveRiskLevel(double riskScore) {
        if (riskScore >= riskProperties.getCriticalThreshold()) return "CRITICAL";
        if (riskScore >= riskProperties.getHighThreshold()) return "HIGH";
        if (riskScore >= riskProperties.getMediumThreshold()) return "MEDIUM";
        return "LOW";
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    public Map<String, Object> buildInferenceDiagnostics() {
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("enabled", inferenceConfig.isInferenceEnabled());
        diag.put("tabularEnabled", tabularProperties.isEnabled());
        diag.put("sequenceEnabled", sequenceProperties.isEnabled());
        diag.put("liveFastModeEnabled", inferenceConfig.isLiveFastModeEnabled());
        diag.put("xgboostEnabled", tabularProperties.isXgboostEnabled());
        diag.put("lightgbmEnabled", tabularProperties.isLightgbmEnabled());
        diag.put("catboostEnabled", tabularProperties.isCatboostEnabled());
        diag.put("oneClassSvmEnabled", tabularProperties.isOneclasssvmEnabled());
        diag.put("transformerEnabled", sequenceProperties.isTransformerEnabled());
        diag.put("tcnEnabled", sequenceProperties.isTcnEnabled());
        diag.put("churnEnabled", churnProperties.isEnabled());
        diag.put("forecastEnabled", forecastProperties.isEnabled());
        diag.put("lastLiveFastModeSkipReason", lastLiveFastModeSkipReason);
        diag.put("optionalModelsSkippedTotal", optionalModelsSkippedTotal.get());
        diag.put("executors", executorManager.diagnostics());
        diag.put("artifactLoadCountByModel", Map.copyOf(modelArtifactLoadCountByModel));
        return diag;
    }

    public void incrementArtifactLoadCount(String modelName) {
        modelArtifactLoadCountByModel.computeIfAbsent(modelName, k -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, Long> getLastTimingBreakdown() {
        return Map.copyOf(lastTimingBreakdown.get());
    }
}