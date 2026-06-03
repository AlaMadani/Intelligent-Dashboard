package com.noveocare.dataprocessor.ai.tabular;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder(toBuilder = true)
public class TabularAnomalyResult {
    Double xgboostAnomalyScore;
    Double xgboostAnomalyScore100;
    String xgboostArtifact;
    Long xgboostLatencyMs;
    Double lightgbmAlertScore;
    Double lightgbmAlertScore100;
    String lightgbmArtifact;
    Long lightgbmLatencyMs;
    Double catboostAnomalyScore;
    Double catboostAnomalyScore100;
    String catboostArtifact;
    Long catboostLatencyMs;
    Double oneClassSvmNoveltyScoreRaw;
    Double oneClassSvmNoveltyScore100;
    String oneClassSvmArtifact;
    Long oneClassSvmLatencyMs;
    List<String> availableModels;
    List<String> unavailableModels;
    List<String> modelWarnings;
    Map<String, Long> latencyByModel;
    List<String> tabularFeatureWarnings;

    public static TabularAnomalyResult unavailable(List<String> warnings) {
        return TabularAnomalyResult.builder()
                .availableModels(List.of())
                .unavailableModels(List.of("xgboost", "lightgbm", "catboost", "oneclasssvm"))
                .modelWarnings(warnings == null ? List.of() : warnings)
                .latencyByModel(Map.of())
                .tabularFeatureWarnings(List.of())
                .build();
    }
}
