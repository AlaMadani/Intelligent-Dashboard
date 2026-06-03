package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactHealth;
import com.noveocare.dataprocessor.ai.churn.ChurnInferenceService;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.ai.sequence.SequenceFieldCoverageMonitor;
import com.noveocare.dataprocessor.ai.sequence.SequenceOnnxInferenceService;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyInferenceService;
import com.noveocare.dataprocessor.ai.tabular.TabularFieldCoverageMonitor;
import com.noveocare.dataprocessor.config.AiChurnProperties;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.config.AiLlmExplanationProperties;
import com.noveocare.dataprocessor.config.AiPersonaProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
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
        return payload;
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
