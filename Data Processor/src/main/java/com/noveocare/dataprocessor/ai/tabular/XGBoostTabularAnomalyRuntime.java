package com.noveocare.dataprocessor.ai.tabular;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.tree.XGBoostJsonPredictor;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class XGBoostTabularAnomalyRuntime implements TabularAnomalyModelRuntime {
    private final RuntimeArtifactService artifactService;
    private final AiTabularAnomalyProperties properties;
    private XGBoostJsonPredictor predictor;
    private final List<String> loadWarnings = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (!properties.isEnabled() || !properties.isXgboostEnabled()) {
            loadWarnings.add("xgboost_anomaly_disabled");
            return;
        }
        if (!artifactService.modelExists(RuntimeArtifactService.TABULAR_XGBOOST_JSON)) {
            loadWarnings.add("xgboost_anomaly_artifact_missing");
            return;
        }
        try {
            predictor = new XGBoostJsonPredictor(artifactService.readModelJson(RuntimeArtifactService.TABULAR_XGBOOST_JSON, JsonNode.class));
            log.info("Loaded XGBoost anomaly ranking JSON trees={}", predictor.treeCount());
        } catch (Exception ex) {
            loadWarnings.add("xgboost_anomaly_runtime_unavailable");
            log.warn("XGBoost anomaly ranking runtime unavailable", ex);
        }
    }

    @Override
    public TabularModelScore score(TabularAnomalyFeatureVector vector) {
        if (!isAvailable()) {
            return TabularModelScore.unavailable(modelName(), artifactName(), firstWarning("xgboost_anomaly_unavailable"));
        }
        long started = System.nanoTime();
        try {
            double score = clamp01(predictor.predict(vector.getScaledValues()));
            return TabularModelScore.builder()
                    .modelName(modelName())
                    .artifactName(artifactName())
                    .available(true)
                    .score(score)
                    .score100(score * 100.0)
                    .rawScore(score)
                    .latencyMs(elapsedMillis(started))
                    .warnings(List.of())
                    .build();
        } catch (Exception ex) {
            return TabularModelScore.unavailable(modelName(), artifactName(), "xgboost_anomaly_score_failed");
        }
    }

    @Override
    public boolean isAvailable() {
        return predictor != null;
    }

    @Override
    public String modelName() {
        return "xgboost";
    }

    @Override
    public String artifactName() {
        return "anomaly_xgboost.json";
    }

    private String firstWarning(String fallback) {
        return loadWarnings.isEmpty() ? fallback : loadWarnings.get(0);
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
