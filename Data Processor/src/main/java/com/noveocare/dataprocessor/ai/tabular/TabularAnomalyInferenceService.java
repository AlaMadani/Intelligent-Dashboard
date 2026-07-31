package com.noveocare.dataprocessor.ai.tabular;

import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import com.noveocare.dataprocessor.inference.InferenceExecutorManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates tabular anomaly model inference across all registered runtimes
 * (XGBoost, LightGBM, CatBoost, One-Class SVM) with async execution, timeouts,
 * and circuit-breaker support.
 */
@Service
@RequiredArgsConstructor
public class TabularAnomalyInferenceService {
    private static final Logger log = LoggerFactory.getLogger(TabularAnomalyInferenceService.class);
    /* ---- Dependencies ---- */
    private final List<TabularAnomalyModelRuntime> runtimes;
    private final AiTabularAnomalyProperties tabularProperties;
    private final InferenceExecutorManager executorManager;

    /* ========== Public API ========== */

    /* Scores a feature vector against all enabled tabular models and aggregates results. */
    public TabularAnomalyResult score(TabularAnomalyFeatureVector vector) {
        List<String> available = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<String> warnings = new ArrayList<>(vector == null || vector.getWarnings() == null ? List.of() : vector.getWarnings());
        Map<String, Long> latency = new LinkedHashMap<>();
        Map<String, TabularModelScore> scores = new LinkedHashMap<>();

        List<ModelSubmission> submissions = new ArrayList<>();
        for (TabularAnomalyModelRuntime runtime : runtimes) {
            String modelName = runtime.modelName();
            if (!isModelEnabled(modelName)) {
                TabularModelScore disabled = TabularModelScore.unavailable(modelName, runtime.artifactName(), modelName + "_disabled");
                scores.put(modelName, disabled);
                unavailable.add(modelName);
                continue;
            }
            if (executorManager.isCircuitOpen(modelName)) {
                TabularModelScore circuit = TabularModelScore.unavailable(modelName, runtime.artifactName(), modelName + "_circuit_open");
                scores.put(modelName, circuit);
                unavailable.add(modelName);
                warnings.add(modelName + "_circuit_open");
                log.warn("{} circuit open, skipping inference", modelName);
                continue;
            }
            submissions.add(new ModelSubmission(modelName, runtime, System.nanoTime()));
        }

        for (ModelSubmission sub : submissions) {
            String modelName = sub.modelName;
            long submitTs = sub.submitTs;
            Future<TabularModelScore> future = executorManager.submitAsync("tabular", modelName,
                    () -> sub.runtime.score(vector));
            long enqueueMs = (System.nanoTime() - submitTs) / 1_000_000L;
            sub.queueWaitMs = enqueueMs;

            if (future == null) {
                TabularModelScore rejected = TabularModelScore.unavailable(modelName, sub.runtime.artifactName(), modelName + "_queue_full");
                scores.put(modelName, rejected);
                unavailable.add(modelName);
                continue;
            }
            sub.startTs = System.nanoTime();
            sub.future = future;
        }

        for (ModelSubmission sub : submissions) {
            if (sub.future == null) continue;
            String modelName = sub.modelName;
            long timeoutMs = tabularProperties.getTimeoutMs();
            long started = sub.startTs;
            long submitWaitMs = sub.queueWaitMs;

            TabularModelScore score;
            try {
                score = sub.future.get(timeoutMs, TimeUnit.MILLISECONDS);
                long runMs = (System.nanoTime() - started) / 1_000_000L;
                log.info("TABULAR_MODEL_SCORE model={} queueWaitMs={} execMs={} status=OK",
                        modelName, submitWaitMs, runMs);
            } catch (TimeoutException e) {
                sub.future.cancel(true);
                score = TabularModelScore.unavailable(modelName, sub.runtime.artifactName(), modelName + "_timeout");
                executorManager.recordTimeout(modelName);
                executorManager.checkAndOpenCircuit(modelName, 3, 60000);
                long runMs = (System.nanoTime() - started) / 1_000_000L;
                log.warn("TABULAR_MODEL_SCORE model={} execMs={} status=TIMEOUT", modelName, runMs);
            } catch (ExecutionException e) {
                score = TabularModelScore.unavailable(modelName, sub.runtime.artifactName(), modelName + "_runtime_error");
                long runMs = (System.nanoTime() - started) / 1_000_000L;
                log.warn("TABULAR_MODEL_SCORE model={} execMs={} status=FAILED", modelName, runMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                score = TabularModelScore.unavailable(modelName, sub.runtime.artifactName(), modelName + "_interrupted");
                long runMs = (System.nanoTime() - started) / 1_000_000L;
                log.warn("TABULAR_MODEL_SCORE model={} execMs={} status=INTERRUPTED", modelName, runMs);
            }
            long elapsedMs = (System.nanoTime() - sub.submitTs) / 1_000_000L;
            scores.put(modelName, score);
            latency.put(modelName, score != null ? score.getLatencyMs() : elapsedMs);
            if (score.isAvailable()) {
                available.add(modelName);
            } else {
                unavailable.add(modelName);
                warnings.addAll(score.getWarnings() == null ? List.of() : score.getWarnings());
            }
        }

        TabularModelScore xgb = scores.get("xgboost");
        TabularModelScore lgbm = scores.get("lightgbm");
        TabularModelScore cat = scores.get("catboost");
        TabularModelScore svm = scores.get("oneclasssvm");
        return TabularAnomalyResult.builder()
                .xgboostAnomalyScore(score(xgb))
                .xgboostAnomalyScore100(score100(xgb))
                .xgboostArtifact(artifact(xgb))
                .xgboostLatencyMs(latency(xgb))
                .lightgbmAlertScore(score(lgbm))
                .lightgbmAlertScore100(score100(lgbm))
                .lightgbmArtifact(artifact(lgbm))
                .lightgbmLatencyMs(latency(lgbm))
                .catboostAnomalyScore(score(cat))
                .catboostAnomalyScore100(score100(cat))
                .catboostArtifact(artifact(cat))
                .catboostLatencyMs(latency(cat))
                .oneClassSvmNoveltyScoreRaw(raw(svm))
                .oneClassSvmNoveltyScore100(score100(svm))
                .oneClassSvmArtifact(artifact(svm))
                .oneClassSvmLatencyMs(latency(svm))
                .availableModels(List.copyOf(available))
                .unavailableModels(List.copyOf(unavailable))
                .modelWarnings(warnings.stream().distinct().toList())
                .latencyByModel(latency)
                .tabularFeatureWarnings(vector == null ? List.of() : vector.getWarnings())
                .build();
    }

    /* Convenience methods to check individual model availability. */
    public boolean xgboostAvailable() {
        return isAvailable("xgboost");
    }

    public boolean lightgbmAvailable() {
        return isAvailable("lightgbm");
    }

    public boolean catboostAvailable() {
        return isAvailable("catboost");
    }

    public boolean oneClassSvmAvailable() {
        return isAvailable("oneclasssvm");
    }

    /* ========== Private helpers ========== */

    /* Checks if any runtime with the given name is available. */
    private boolean isAvailable(String modelName) {
        return runtimes.stream().anyMatch(runtime -> modelName.equals(runtime.modelName()) && runtime.isAvailable());
    }

    /* Null-safe score extraction helpers. */
    private Double score(TabularModelScore score) {
        return score == null || !score.isAvailable() ? null : score.getScore();
    }

    private Double score100(TabularModelScore score) {
        return score == null || !score.isAvailable() ? null : score.getScore100();
    }

    private Double raw(TabularModelScore score) {
        return score == null || !score.isAvailable() ? null : score.getRawScore();
    }

    private String artifact(TabularModelScore score) {
        return score == null ? null : score.getArtifactName();
    }

    private Long latency(TabularModelScore score) {
        return score == null ? null : score.getLatencyMs();
    }

    /* Checks property-based enable/disable for each model. */
    private boolean isModelEnabled(String modelName) {
        if (!tabularProperties.isEnabled()) return false;
        return switch (modelName) {
            case "xgboost" -> tabularProperties.isXgboostEnabled();
            case "lightgbm" -> tabularProperties.isLightgbmEnabled();
            case "catboost" -> tabularProperties.isCatboostEnabled();
            case "oneclasssvm" -> tabularProperties.isOneclasssvmEnabled();
            default -> true;
        };
    }

    /* Internal record for tracking async model submissions. */
    private static class ModelSubmission {
        final String modelName;
        final TabularAnomalyModelRuntime runtime;
        final long submitTs;
        long queueWaitMs;
        long startTs;
        Future<TabularModelScore> future;

        ModelSubmission(String modelName, TabularAnomalyModelRuntime runtime, long submitTs) {
            this.modelName = modelName;
            this.runtime = runtime;
            this.submitTs = submitTs;
        }
    }
}