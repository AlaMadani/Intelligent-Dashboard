package com.noveocare.dataprocessor.ai.tabular;

import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.tree.LightGbmTxtPredictor;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * LightGBM tabular alert runtime. Parses a text-format LightGBM model and
 * scores feature vectors.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LightGbmTabularAlertRuntime implements TabularAnomalyModelRuntime {
    /* ---- Dependencies ---- */
    private final RuntimeArtifactService artifactService;
    private final AiTabularAnomalyProperties properties;
    private LightGbmTxtPredictor predictor;
    private final List<String> loadWarnings = new ArrayList<>();

    /* ========== Initialisation ========== */

    /* Loads the LightGBM text model; gracefully handles absence. */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled() || !properties.isLightgbmEnabled()) {
            loadWarnings.add("lightgbm_alert_disabled");
            return;
        }
        if (!artifactService.modelExists(RuntimeArtifactService.TABULAR_LIGHTGBM)) {
            loadWarnings.add("lightgbm_alert_artifact_missing");
            return;
        }
        try {
            String text = new String(artifactService.readModelBytes(RuntimeArtifactService.TABULAR_LIGHTGBM), StandardCharsets.UTF_8);
            predictor = new LightGbmTxtPredictor(text);
            if (predictor.treeCount() == 0) {
                predictor = null;
                loadWarnings.add("lightgbm_alert_no_trees_parsed");
                return;
            }
            log.info("Loaded LightGBM alerting TXT trees={}", predictor.treeCount());
        } catch (Exception ex) {
            loadWarnings.add("lightgbm_alert_runtime_unavailable");
            log.warn("LightGBM alerting runtime unavailable", ex);
        }
    }

    /* ========== TabularAnomalyModelRuntime implementation ========== */

    @Override
    public TabularModelScore score(TabularAnomalyFeatureVector vector) {
        if (!isAvailable()) {
            return TabularModelScore.unavailable(modelName(), artifactName(), firstWarning("lightgbm_alert_unavailable"));
        }
        long started = System.nanoTime();
        try {
            double score = clamp01(predictor.predictProbability(vector.getScaledValues()));
            return TabularModelScore.builder()
                    .modelName(modelName())
                    .artifactName(artifactName())
                    .available(true)
                    .score(score)
                    .score100(score * 100.0)
                    .rawScore(predictor.predictRaw(vector.getScaledValues()))
                    .latencyMs(elapsedMillis(started))
                    .warnings(List.of())
                    .build();
        } catch (Exception ex) {
            return TabularModelScore.unavailable(modelName(), artifactName(), "lightgbm_alert_score_failed");
        }
    }

    /* Returns true if the predictor was successfully initialised. */
    @Override
    public boolean isAvailable() {
        return predictor != null;
    }

    @Override
    public String modelName() {
        return "lightgbm";
    }

    @Override
    public String artifactName() {
        return "anomaly_lightgbm.txt";
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

    /* Clamps a value to the [0, 1] range. */
    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
