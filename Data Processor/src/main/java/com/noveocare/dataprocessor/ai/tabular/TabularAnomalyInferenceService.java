package com.noveocare.dataprocessor.ai.tabular;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TabularAnomalyInferenceService {
    private final List<TabularAnomalyModelRuntime> runtimes;

    public TabularAnomalyResult score(TabularAnomalyFeatureVector vector) {
        List<String> available = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<String> warnings = new ArrayList<>(vector == null || vector.getWarnings() == null ? List.of() : vector.getWarnings());
        Map<String, Long> latency = new LinkedHashMap<>();
        Map<String, TabularModelScore> scores = new LinkedHashMap<>();

        for (TabularAnomalyModelRuntime runtime : runtimes) {
            TabularModelScore score = runtime.score(vector);
            scores.put(runtime.modelName(), score);
            latency.put(runtime.modelName(), score.getLatencyMs());
            if (score.isAvailable()) {
                available.add(runtime.modelName());
            } else {
                unavailable.add(runtime.modelName());
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

    private boolean isAvailable(String modelName) {
        return runtimes.stream().anyMatch(runtime -> modelName.equals(runtime.modelName()) && runtime.isAvailable());
    }

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
}
