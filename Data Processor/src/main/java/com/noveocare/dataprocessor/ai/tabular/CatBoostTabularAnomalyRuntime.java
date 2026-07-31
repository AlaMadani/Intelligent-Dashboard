package com.noveocare.dataprocessor.ai.tabular;

import ai.catboost.CatBoostModel;
import ai.catboost.CatBoostPredictions;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CatBoost tabular anomaly detection runtime. Loads a .cbm model and scores
 * feature vectors.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CatBoostTabularAnomalyRuntime implements TabularAnomalyModelRuntime {
    /* ---- Dependencies ---- */
    private final RuntimeArtifactService artifactService;
    private final AiTabularAnomalyProperties properties;
    private CatBoostModel model;
    private final List<String> loadWarnings = new ArrayList<>();

    /* ========== Initialisation ========== */

    /* Loads the CatBoost model file; gracefully handles absence. */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled() || !properties.isCatboostEnabled()) {
            loadWarnings.add("catboost_anomaly_disabled");
            return;
        }
        if (!artifactService.modelExists(RuntimeArtifactService.TABULAR_CATBOOST)) {
            loadWarnings.add("catboost_anomaly_artifact_missing");
            return;
        }
        try {
            Path path = artifactService.copyModelToTempFile(RuntimeArtifactService.TABULAR_CATBOOST);
            model = CatBoostModel.loadModel(path.toString());
            log.info("Loaded CatBoost anomaly CBM");
        } catch (Exception ex) {
            loadWarnings.add("catboost_anomaly_runtime_unavailable");
            log.warn("CatBoost anomaly runtime unavailable", ex);
        }
    }

    /* ========== TabularAnomalyModelRuntime implementation ========== */

    @Override
    public TabularModelScore score(TabularAnomalyFeatureVector vector) {
        if (!isAvailable()) {
            return TabularModelScore.unavailable(modelName(), artifactName(), firstWarning("catboost_anomaly_unavailable"));
        }
        long started = System.nanoTime();
        try {
            float[] floatVector = new float[vector.getScaledValues().length];
            for (int i = 0; i < floatVector.length; i++) {
                floatVector[i] = (float) vector.getScaledValues()[i];
            }
            CatBoostPredictions predictions = model.predict(new float[][]{floatVector}, new String[][]{new String[0]});
            double raw = predictions.get(0, 0);
            double score = raw >= 0.0 && raw <= 1.0 ? raw : sigmoid(raw);
            return TabularModelScore.builder()
                    .modelName(modelName())
                    .artifactName(artifactName())
                    .available(true)
                    .score(score)
                    .score100(score * 100.0)
                    .rawScore(raw)
                    .latencyMs(elapsedMillis(started))
                    .warnings(List.of())
                    .build();
        } catch (Exception ex) {
            return TabularModelScore.unavailable(modelName(), artifactName(), "catboost_anomaly_score_failed");
        }
    }

    /* Returns true if the native model handle is non-null. */
    @Override
    public boolean isAvailable() {
        return model != null;
    }

    @Override
    public String modelName() {
        return "catboost";
    }

    @Override
    public String artifactName() {
        return "anomaly_catboost.cbm";
    }

    /* ========== Lifecycle ========== */

    @PreDestroy
    public void close() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception ex) {
                log.warn("Failed to close CatBoost anomaly model cleanly", ex);
            }
        }
    }

    /* ========== Private helpers ========== */

    /* Returns the first load warning or a default fallback. */
    private String firstWarning(String fallback) {
        return loadWarnings.isEmpty() ? fallback : loadWarnings.get(0);
    }

    /* Computes elapsed milliseconds since a nanoTime reference. */
    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    /* Sigmoid function with numeric stability for positive/negative inputs. */
    private double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }
}
