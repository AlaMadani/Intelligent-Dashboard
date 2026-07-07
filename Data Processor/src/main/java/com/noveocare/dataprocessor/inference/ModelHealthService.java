package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactHealth;
import com.noveocare.dataprocessor.ai.churn.ChurnInferenceService;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.ai.sequence.SequenceFieldCoverageMonitor;
import com.noveocare.dataprocessor.ai.sequence.SequenceOnnxInferenceService;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyInferenceService;
import com.noveocare.dataprocessor.ai.tabular.TabularFieldCoverageMonitor;
import com.noveocare.dataprocessor.kafka.AuditTrailConsumer;
import com.noveocare.dataprocessor.config.AiChurnProperties;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.config.AiLlmExplanationProperties;
import com.noveocare.dataprocessor.config.AiPersonaProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.LiveStatsProperties;
import com.noveocare.dataprocessor.config.NextActionPredictionProperties;
import com.noveocare.dataprocessor.config.NextEventPredictionProperties;
import com.noveocare.dataprocessor.config.PerformanceProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.kafka.AlertPublisher;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.redis.RedisSessionBufferService;
import com.noveocare.dataprocessor.service.DashboardSnapshotService;
import com.noveocare.dataprocessor.service.EventIdempotencyService;
import com.noveocare.dataprocessor.service.SessionFinalizationOrchestrator;
import com.noveocare.dataprocessor.service.SessionFinalizationService;
import com.noveocare.dataprocessor.service.DashboardRefreshScheduler;
import com.noveocare.dataprocessor.service.DashboardSnapshotPersistenceService;
import com.noveocare.dataprocessor.service.AlertCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class ModelHealthService {

    private final SequenceOnnxInferenceService onnxInferenceService;
    private final TabularAnomalyInferenceService tabularAnomalyInferenceService;
    private final ChurnInferenceService churnInferenceService;
    private final ForecastRuntimeService forecastRuntimeService;
    private final RuntimeArtifactService artifactService;
    private final SequenceFieldCoverageMonitor coverageMonitor;
    private final TabularFieldCoverageMonitor tabularCoverageMonitor;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;
    private final AiSequenceProperties sequenceProperties;
    private final AiTabularAnomalyProperties tabularProperties;
    private final AiChurnProperties churnProperties;
    private final AiForecastProperties forecastProperties;
    private final AiPersonaProperties personaProperties;
    private final AiLlmExplanationProperties llmProperties;
    private final SessionFinalizationService sessionFinalizationService;
    private final EventIdempotencyService eventIdempotencyService;
    private final SessionFinalizationOrchestrator finalizationOrchestrator;
    private final AlertPublisher alertPublisher;
    private final LiveStatsProperties liveStatsProperties;
    private final NextActionPredictionProperties nextActionPredictionProperties;
    private final NextEventPredictionProperties nextEventPredictionProperties;
    private final RedisSessionBufferService redisSessionBufferService;
    private final ObjectFactory<AuditTrailConsumer> auditTrailConsumerFactory;
    private final PerformanceProperties performanceProperties;
    private final DashboardSnapshotService dashboardSnapshotService;
    private final DashboardSnapshotPersistenceService dashboardSnapshotPersistenceService;
    private final InferenceConfig inferenceConfig;
    private final InferenceExecutorManager executorManager;
    private final DashboardRefreshScheduler dashboardRefreshScheduler;
    private final InferenceBenchmarkService inferenceBenchmarkService;
    private final AlertCacheService alertCacheService;

    private final AtomicLong inferenceErrorCount = new AtomicLong();
    private final Map<String, Instant> runtimeLastInferenceAt = new ConcurrentHashMap<>();
    private final Map<String, Boolean> runtimeLastSucceeded = new ConcurrentHashMap<>();
    private final Map<String, String> runtimeLastError = new ConcurrentHashMap<>();
    private volatile Instant lastInferenceAt;
    private volatile String fallbackMode = "normal";

    public void recordInference(String mode) {
        lastInferenceAt = Instant.now();
        fallbackMode = mode == null ? "normal" : mode;
        publish();
    }

    public void recordError(String mode) {
        inferenceErrorCount.incrementAndGet();
        fallbackMode = mode == null ? "error" : mode;
        publish();
    }

    public void recordRuntimeSuccess(String runtimeKey) {
        if (runtimeKey == null || runtimeKey.isBlank()) {
            return;
        }
        runtimeLastInferenceAt.put(runtimeKey, Instant.now());
        runtimeLastSucceeded.put(runtimeKey, true);
        runtimeLastError.remove(runtimeKey);
    }

    public void recordRuntimeError(String runtimeKey, String error) {
        if (runtimeKey == null || runtimeKey.isBlank()) {
            return;
        }
        runtimeLastInferenceAt.put(runtimeKey, Instant.now());
        runtimeLastSucceeded.put(runtimeKey, false);
        runtimeLastError.put(runtimeKey, error == null || error.isBlank() ? "runtime_unavailable" : error);
    }

    public void publish() {
        redisCacheService.setJson(CacheKeys.aiRuntimeHealthKey(), snapshot(), cacheProperties.getLiveStats());
    }

    public Map<String, Object> snapshot() {
        RuntimeArtifactHealth artifactHealth = artifactService.getArtifactHealth();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("runtimeVersion", artifactHealth == null ? "v3.6.1" : artifactHealth.getRuntimeVersion());
        payload.put("artifactBasePath", artifactHealth == null ? null : artifactHealth.getArtifactBasePath());
        payload.put("transformerLoaded", onnxInferenceService.transformerLoaded());
        payload.put("tcnLoaded", onnxInferenceService.tcnLoaded());
        payload.put("xgboostAnomalyLoaded", tabularAnomalyInferenceService.xgboostAvailable());
        payload.put("lightgbmAlertLoaded", tabularAnomalyInferenceService.lightgbmAvailable());
        payload.put("catboostAnomalyLoaded", tabularAnomalyInferenceService.catboostAvailable());
        payload.put("oneClassSvmLoaded", tabularAnomalyInferenceService.oneClassSvmAvailable());
        payload.put("churnExtraTreesLoaded", churnInferenceService.isAvailable());
        payload.put("forecastRidgeLoaded", forecastRuntimeService.ridgeLoaded());
        payload.put("forecastXGBoostLoaded", forecastRuntimeService.xgboostLoaded());
        payload.put("personaEnabled", false);
        payload.put("personaSkippedReason", artifactHealth == null ? null : artifactHealth.getPersonaSkippedReason());
        payload.put("llmExplanationInDataprocessor", false);
        payload.put("llmEvidencePayloadEnabled", true);
        payload.put("missingArtifacts", artifactHealth == null ? List.of() : artifactHealth.getMissingArtifacts());
        payload.put("warnings", artifactHealth == null ? List.of() : artifactHealth.getWarnings());
        payload.put("lastInferenceTimestamp", lastInferenceAt == null ? null : lastInferenceAt.toString());
        payload.put("inferenceErrorCount", inferenceErrorCount.get());
        payload.put("fallbackMode", fallbackMode);
        payload.put("modelHealth", modelHealth());
        payload.put("fieldCoverage", Map.of(
                "sequence", coverageMonitor.snapshot(),
                "tabular", tabularCoverageMonitor.snapshot()));
        payload.put("highUnknownFieldWarnings", coverageMonitor.highUnknownWarnings());
        payload.put("sessionFinalization", sessionFinalizationService.diagnosticsSnapshot());
        payload.put("kafka", buildKafkaDiagnostics());
        payload.put("idempotency", buildIdempotencyDiagnostics());
        payload.put("stats", buildStatsDiagnostics());
        payload.put("nextActionPrediction", buildNextActionPredictionDiagnostics());
        payload.put("nextEventPrediction", buildNextEventPredictionDiagnostics());
        payload.put("inference", buildInferenceDiagnostics());
        payload.put("dashboard", buildDashboardDiagnostics());
        payload.put("performance", buildPerformanceDiagnostics());
        payload.put("modelLatency", buildModelLatencyDiagnostics());
        payload.put("alertCacheConsistency", alertCacheService.consistencyDiagnostics());
        return payload;
    }

    private Map<String, Object> buildInferenceDiagnostics() {
        Map<String, Object> inference = new LinkedHashMap<>();
        inference.put("enabled", inferenceConfig.isInferenceEnabled());
        inference.put("sequenceEnabled", inferenceConfig.isSequenceEnabled());
        inference.put("transformerEnabled", inferenceConfig.isTransformerEnabled());
        inference.put("tcnEnabled", inferenceConfig.isTcnEnabled());
        inference.put("sequenceDisabledReason", inferenceConfig.isSequenceEnabled() ? null : "live_fast_mode");
        inference.put("tabularEnabled", inferenceConfig.isTabularEnabled());
        inference.put("churnEnabled", inferenceConfig.isChurnEnabled());
        inference.put("forecastEnabled", inferenceConfig.isForecastEnabled());
        inference.put("liveFastModeEnabled", inferenceConfig.isLiveFastModeEnabled());
        inference.put("liveFastModeSkipTransformer", inferenceConfig.isLiveFastModeSkipTransformer());
        inference.put("liveFastModeSkipSequence", inferenceConfig.isLiveFastModeSkipSequence());
        inference.put("disableModelForRunAfterCircuitOpen", inferenceConfig.isDisableModelForRunAfterCircuitOpen());
        inference.put("debugBenchmarkEnabled", inferenceConfig.isDebugBenchmarkEnabled());
        inference.put("modelTimeoutCountByModel", executorManager.getTimeoutCount("transformer"));
        inference.put("modelCircuitOpenByModel", executorManager.isCircuitOpenRaw("transformer"));
        inference.put("lastTimedOutModel", executorManager.getLastTimedOutAt("transformer") != null ? "transformer" : null);
        inference.put("lastTimedOutAt", executorManager.getLastTimedOutAt("transformer") != null
                ? executorManager.getLastTimedOutAt("transformer").toString() : null);
        inference.put("executors", executorManager.diagnostics());
        inference.put("circuitBreakers", executorManager.circuitBreakerDiagnostics());
        inference.put("sequenceBenchmark", inferenceBenchmarkService.snapshot());
        return inference;
    }

    private Map<String, Object> buildDashboardDiagnostics() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("dirtyFlags", Map.of(
                "alertsDirty", dashboardSnapshotService.isAlertsDirty(),
                "riskySessionsDirty", dashboardSnapshotService.isRiskySessionsDirty(),
                "securityOverviewDirty", dashboardSnapshotService.isSecurityOverviewDirty()));
        dashboard.put("schedulerLastRunAt", dashboardRefreshScheduler.getLastRunAt() != null
                ? dashboardRefreshScheduler.getLastRunAt().toString() : null);
        dashboard.put("schedulerRunCount", dashboardRefreshScheduler.getRunCount());
        dashboard.put("lastRefreshAttemptAt", dashboardSnapshotService.getLastRefreshAttemptAt() != null
                ? dashboardSnapshotService.getLastRefreshAttemptAt().toString() : null);
        dashboard.put("lastRefreshSuccessAt", dashboardSnapshotService.getDashboardLastRefreshAt() != null
                ? dashboardSnapshotService.getDashboardLastRefreshAt().toString() : null);
        dashboard.put("lastRefreshError", dashboardSnapshotService.getLastRefreshError());
        dashboard.put("refreshSuccessCount", dashboardSnapshotService.getRefreshSuccessCount());
        dashboard.put("refreshFailureCount", dashboardSnapshotService.getRefreshFailureCount());
        dashboard.put("refreshSkippedDueToRateLimit", dashboardSnapshotService.getDashboardRefreshSkippedDueToRateLimit());
        dashboard.put("lastAlertsRefreshMs", dashboardSnapshotService.getLastAlertsRefreshMs());
        dashboard.put("lastSecurityOverviewRefreshMs", dashboardSnapshotService.getLastSecurityOverviewRefreshMs());
        dashboard.put("lastRiskySessionsRefreshMs", dashboardSnapshotService.getLastRiskySessionsRefreshMs());
        dashboard.put("lastTotalDashboardRefreshMs", dashboardSnapshotService.getLastTotalDashboardRefreshMs());
        dashboard.put("lastSlowDashboardView", dashboardSnapshotService.getLastSlowDashboardView());
        dashboard.put("lastSlowDashboardViewMs", dashboardSnapshotService.getLastSlowDashboardViewMs());
        dashboard.put("refreshAlreadyRunningSkipped", dashboardSnapshotService.getRefreshAlreadyRunningSkipped());
        dashboard.put("lastRefreshStartedAt", dashboardSnapshotService.getLastRefreshStartedAt() != null
                ? dashboardSnapshotService.getLastRefreshStartedAt().toString() : null);
        dashboard.put("lastRefreshCompletedAt", dashboardSnapshotService.getLastRefreshCompletedAt() != null
                ? dashboardSnapshotService.getLastRefreshCompletedAt().toString() : null);
        dashboard.put("snapshotSqlWriteSuccessTotal", dashboardSnapshotPersistenceService.getSqlWriteSuccessTotal());
        dashboard.put("snapshotSqlWriteFailureTotal", dashboardSnapshotPersistenceService.getSqlWriteFailureTotal());
        dashboard.put("snapshotSqlLastWriteAt", dashboardSnapshotPersistenceService.getSqlLastWriteAt() != null
                ? dashboardSnapshotPersistenceService.getSqlLastWriteAt().toString() : null);
        dashboard.put("snapshotSqlLastFailureAt", dashboardSnapshotPersistenceService.getSqlLastFailureAt() != null
                ? dashboardSnapshotPersistenceService.getSqlLastFailureAt().toString() : null);
        return dashboard;
    }

    private Map<String, Object> buildKafkaDiagnostics() {
        Map<String, Object> kafka = new LinkedHashMap<>();
        Map<String, Object> consumerDiag = auditTrailConsumerFactory.getObject().diagnosticsSnapshot();
        kafka.putAll(consumerDiag);
        return kafka;
    }

    private Map<String, Object> buildPerformanceDiagnostics() {
        Map<String, Object> perf = new LinkedHashMap<>();
        Map<String, Object> kafkaDiag = auditTrailConsumerFactory.getObject().diagnosticsSnapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> kafkaPerf = kafkaDiag.get("performance") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : Map.of();

        boolean hasData = false;
        for (String key : List.of("eventProcessingMsAvg", "eventProcessingMsP95", "recordsProcessedPerSecond")) {
            if (kafkaPerf.get(key) != null) {
                hasData = true;
                break;
            }
        }
        perf.put("available", hasData);
        if (!hasData) {
            perf.put("reason", "not_instrumented_yet");
            return perf;
        }

        perf.put("eventProcessingMsAvg", kafkaPerf.get("eventProcessingMsAvg"));
        perf.put("eventProcessingMsP95", kafkaPerf.get("eventProcessingMsP95"));
        perf.put("modelInferenceMsAvg", kafkaPerf.get("modelInferenceMsAvg"));
        perf.put("modelInferenceMsP95", kafkaPerf.get("modelInferenceMsP95"));
        perf.put("sequenceMsAvg", kafkaPerf.get("sequenceMsAvg"));
        perf.put("sequenceMsP95", kafkaPerf.get("sequenceMsP95"));
        perf.put("tabularMsAvg", kafkaPerf.get("tabularMsAvg"));
        perf.put("tabularMsP95", kafkaPerf.get("tabularMsP95"));
        perf.put("historyFetchMsAvg", kafkaPerf.get("historyFetchMsAvg"));
        perf.put("historyFetchMsP95", kafkaPerf.get("historyFetchMsP95"));
        perf.put("rulesMsAvg", kafkaPerf.get("rulesMsAvg"));
        perf.put("rulesMsP95", kafkaPerf.get("rulesMsP95"));
        perf.put("finalizationMsAvg", kafkaPerf.get("finalizationMsAvg"));
        perf.put("finalizationMsP95", kafkaPerf.get("finalizationMsP95"));
        perf.put("alertPublishMsAvg", kafkaPerf.get("alertPublishMsAvg"));
        perf.put("alertPublishMsP95", kafkaPerf.get("alertPublishMsP95"));
        perf.put("kafkaEventAgeReceiveMsAvg", kafkaPerf.get("kafkaEventAgeReceiveMsAvg"));
        perf.put("kafkaEventAgeReceiveMsP95", kafkaPerf.get("kafkaEventAgeReceiveMsP95"));
        perf.put("recordsProcessedPerSecond", kafkaPerf.get("recordsProcessedPerSecond"));
        perf.put("kafkaLagCached", kafkaPerf.get("kafkaLagCached"));
        perf.put("performanceSummaryLastRunAt", kafkaPerf.get("performanceSummaryLastRunAt"));
        perf.put("performanceSummaryRunCount", kafkaPerf.get("performanceSummaryRunCount"));
        perf.put("lastSlowEventId", kafkaPerf.get("lastSlowEventId"));
        perf.put("lastSlowEventAction", kafkaPerf.get("lastSlowEventAction"));

        perf.put("dashboardLastRefreshAt", dashboardSnapshotService.getDashboardLastRefreshAt() != null
                ? dashboardSnapshotService.getDashboardLastRefreshAt().toString() : null);
        perf.put("loadSheddingMode", fallbackMode);
        perf.put("sequenceMode", sequenceProperties.isEnabled()
                ? (sequenceProperties.isRunBoth() ? "run_both"
                        : sequenceProperties.getPrimaryModel())
                : "disabled");
        return perf;
    }

    private Map<String, Object> buildModelLatencyDiagnostics() {
        Map<String, Object> latency = new LinkedHashMap<>();
        Map<String, Object> benchmark = inferenceBenchmarkService.snapshot();
        boolean hasBenchmark = benchmark.containsKey("xgboost") || benchmark.containsKey("lightgbm")
                || benchmark.containsKey("catboost") || benchmark.containsKey("oneclasssvm")
                || benchmark.containsKey("churn");
        latency.put("available", hasBenchmark);
        if (!hasBenchmark) {
            latency.put("reason", "not_instrumented_yet");
            return latency;
        }
        latency.put("benchmarkLastRunAt", benchmark.get("lastRunAt"));
        latency.put("xgboostMs", benchmark.get("xgboost"));
        latency.put("lightgbmMs", benchmark.get("lightgbm"));
        latency.put("catboostMs", benchmark.get("catboost"));
        latency.put("oneclasssvmMs", benchmark.get("oneclasssvm"));
        latency.put("churnMs", benchmark.get("churn"));
        latency.put("benchmarkWarnings", benchmark.get("warnings"));
        return latency;
    }

    private Map<String, Object> buildIdempotencyDiagnostics() {
        Map<String, Object> idempotency = new LinkedHashMap<>();
        idempotency.put("duplicateEventsSkipped", eventIdempotencyService.getDuplicateEventsSkipped());
        idempotency.put("lastDuplicateEventId", eventIdempotencyService.getLastDuplicateEventId());
        idempotency.put("lastDuplicateEventSessionId", eventIdempotencyService.getLastDuplicateEventSessionId());
        idempotency.put("lastDuplicateEventAt", eventIdempotencyService.getLastDuplicateEventAt() != null
                ? eventIdempotencyService.getLastDuplicateEventAt().toString() : null);
        idempotency.put("duplicateSequenceAppendsSkipped", redisSessionBufferService.getDuplicateSequenceAppendsSkipped());
        idempotency.put("duplicateAlertsSkipped", finalizationOrchestrator.getDuplicateAlertsSkipped());
        idempotency.put("duplicateSqlWritesSkipped", alertPublisher.getDuplicateSqlWritesSkipped());
        idempotency.put("duplicateFinalizationSkipped", finalizationOrchestrator.getDuplicateFinalizationSkipped());
        idempotency.put("duplicateLiveAlertsSkipped", finalizationOrchestrator.getDuplicateLiveAlertsSkipped());
        idempotency.put("duplicateUserAlertsSkipped", finalizationOrchestrator.getDuplicateUserAlertsSkipped());
        return idempotency;
    }

    private Map<String, Object> buildStatsDiagnostics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("liveTimeBasis", liveStatsProperties.getTimeBasis());
        return stats;
    }

    private Map<String, Object> buildNextActionPredictionDiagnostics() {
        Map<String, Object> nap = new LinkedHashMap<>();
        nap.put("enabled", nextActionPredictionProperties.isEnabled());
        nap.put("disabledReason", nextActionPredictionProperties.getDisabledReason());
        nap.put("deprecated", true);
        nap.put("replacedBy", "nextEventPrediction");
        return nap;
    }

    private Map<String, Object> buildNextEventPredictionDiagnostics() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("enabled", nextEventPredictionProperties.isEnabled());
        d.put("model", nextEventPredictionProperties.getModelPreference());
        d.put("topK", nextEventPredictionProperties.getTopK());
        d.put("minContextEvents", nextEventPredictionProperties.getMinContextEvents());
        d.put("affectRiskScore", nextEventPredictionProperties.isAffectRiskScore());
        d.put("writeRedis", nextEventPredictionProperties.isWriteRedis());
        d.put("writeSql", nextEventPredictionProperties.isWriteSql());
        d.put("evaluateDeviation", nextEventPredictionProperties.isEvaluateDeviation());
        d.put("heads", nextEventPredictionProperties.getHeads());
        return d;
    }

    private Map<String, Object> modelHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        boolean transformerExists = artifactService.modelExists(RuntimeArtifactService.TRANSFORMER_MODEL);
        boolean tcnExists = artifactService.modelExists(RuntimeArtifactService.TCN_MODEL);
        boolean xgbExists = artifactService.modelExists(RuntimeArtifactService.TABULAR_XGBOOST_JSON)
                || artifactService.modelExists(RuntimeArtifactService.TABULAR_XGBOOST_UBJ);
        boolean lightgbmExists = artifactService.modelExists(RuntimeArtifactService.TABULAR_LIGHTGBM);
        boolean catboostExists = artifactService.modelExists(RuntimeArtifactService.TABULAR_CATBOOST);
        boolean svmExists = artifactService.modelExists(RuntimeArtifactService.TABULAR_ONECLASS_SVM);
        boolean churnExists = artifactService.modelExists(RuntimeArtifactService.CHURN_MODEL);
        boolean ridgeExists = artifactService.modelExists(RuntimeArtifactService.FORECAST_ANOMALY_RATE_RIDGE);
        boolean forecastXgbExists = artifactService.modelExists(RuntimeArtifactService.FORECAST_TOTAL_EVENTS_XGBOOST_JSON)
                || artifactService.modelExists(RuntimeArtifactService.FORECAST_TOTAL_EVENTS_XGBOOST_UBJ);
        boolean personaExists = artifactService.modelExists(RuntimeArtifactService.PERSONA_MODEL);

        health.put("transformerOnnx", runtimeEntry(
                "Transformer ONNX",
                "transformer_sequence_engine.onnx",
                transformerExists,
                onnxInferenceService.transformerLoaded(),
                sequenceProperties.isEnabled(),
                "transformer"));
        health.put("tcnOnnx", runtimeEntry(
                "TCN ONNX",
                "tcn_sequence_engine.onnx",
                tcnExists,
                onnxInferenceService.tcnLoaded(),
                sequenceProperties.isEnabled(),
                "tcn"));
        health.put("xgboostAnomalyRanking", runtimeEntry(
                "XGBoost anomaly ranking",
                "anomaly_xgboost.json",
                xgbExists,
                tabularAnomalyInferenceService.xgboostAvailable(),
                tabularProperties.isEnabled() && tabularProperties.isXgboostEnabled(),
                "xgboost"));
        health.put("lightgbmAlerting", runtimeEntry(
                "LightGBM alerting",
                "anomaly_lightgbm.txt",
                lightgbmExists,
                tabularAnomalyInferenceService.lightgbmAvailable(),
                tabularProperties.isEnabled() && tabularProperties.isLightgbmEnabled(),
                "lightgbm"));
        health.put("catboostOptionalAnomaly", runtimeEntry(
                "CatBoost optional anomaly",
                "anomaly_catboost.cbm",
                catboostExists,
                tabularAnomalyInferenceService.catboostAvailable(),
                tabularProperties.isEnabled() && tabularProperties.isCatboostEnabled(),
                "catboost"));
        health.put("oneClassSvmNovelty", runtimeEntry(
                "OneClassSVM novelty",
                "anomaly_oneclasssvm.json",
                svmExists,
                tabularAnomalyInferenceService.oneClassSvmAvailable(),
                tabularProperties.isEnabled() && tabularProperties.isOneclasssvmEnabled(),
                "oneclasssvm"));
        health.put("extraTreesChurn", runtimeEntry(
                "ExtraTrees churn",
                "churn_profile_only_ExtraTrees.json",
                churnExists,
                churnInferenceService.isAvailable(),
                churnProperties.isEnabled(),
                "churn"));
        health.put("ridgeAnomalyRateForecast", runtimeEntry(
                "Ridge anomaly-rate forecast",
                "macro_forecaster_anomaly_rate_Ridge.json",
                ridgeExists,
                forecastRuntimeService.ridgeLoaded(),
                forecastProperties.isEnabled(),
                "forecast_ridge"));
        health.put("xgboostTotalEventsForecast", runtimeEntry(
                "XGBoost total-events forecast",
                "macro_forecaster_total_events_XGBoost.json",
                forecastXgbExists,
                forecastRuntimeService.xgboostLoaded(),
                forecastProperties.isEnabled(),
                "forecast_xgboost"));
        health.put("persona", disabledEntry(
                "Persona disabled/skipped",
                personaExists,
                personaProperties.getSkippedReason(),
                "persona"));
        health.put("llmCall", disabledEntry(
                "LLM call disabled in dataprocessor",
                false,
                "LLM explanation is handled by api-service on demand",
                "llm_call"));
        health.put("llmEvidencePayload", runtimeEntry(
                "LLM evidence payload",
                "llm_explanation_config.json",
                artifactService.resourceExists("config/llm_explanation_config.json"),
                llmProperties.isGenerateEvidencePayload(),
                llmProperties.isGenerateEvidencePayload(),
                "llm_evidence_payload"));
        return health;
    }

    private Map<String, Object> runtimeEntry(String displayName,
                                             String artifactName,
                                             boolean artifactExists,
                                             boolean runtimeInitialized,
                                             boolean inferenceEnabledByConfig,
                                             String runtimeKey) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("displayName", displayName);
        entry.put("artifactName", artifactName);
        entry.put("artifactExists", artifactExists);
        entry.put("artifactParsed", runtimeInitialized);
        entry.put("runtimeInitialized", runtimeInitialized);
        entry.put("inferenceEnabledByConfig", inferenceEnabledByConfig);
        entry.put("lastInferenceSucceeded", runtimeLastSucceeded.get(runtimeKey));
        entry.put("lastInferenceError", runtimeLastError.get(runtimeKey));
        Instant last = runtimeLastInferenceAt.get(runtimeKey);
        entry.put("lastInferenceTimestamp", last == null ? null : last.toString());
        entry.put("unavailableReason", unavailableReason(artifactExists, runtimeInitialized, inferenceEnabledByConfig, runtimeKey));
        return entry;
    }

    private Map<String, Object> disabledEntry(String displayName,
                                              boolean artifactExists,
                                              String reason,
                                              String runtimeKey) {
        Map<String, Object> entry = runtimeEntry(displayName, null, artifactExists, false, false, runtimeKey);
        entry.put("unavailableReason", reason);
        return entry;
    }

    private String unavailableReason(boolean artifactExists,
                                     boolean runtimeInitialized,
                                     boolean inferenceEnabledByConfig,
                                     String runtimeKey) {
        String lastError = runtimeLastError.get(runtimeKey);
        if (lastError != null && !lastError.isBlank()) {
            return lastError;
        }
        if (!inferenceEnabledByConfig) {
            return "disabled_by_config";
        }
        if (!artifactExists) {
            return "artifact_missing";
        }
        if (!runtimeInitialized) {
            return "runtime_unavailable_or_parse_failed";
        }
        return null;
    }
}