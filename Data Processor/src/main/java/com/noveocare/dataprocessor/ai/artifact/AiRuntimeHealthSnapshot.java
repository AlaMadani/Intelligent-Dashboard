package com.noveocare.dataprocessor.ai.artifact;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Value
@Builder(toBuilder = true)
public class AiRuntimeHealthSnapshot {
    String schemaVersion;
    String runtimeVersion;
    String artifactBasePath;
    boolean transformerLoaded;
    boolean tcnLoaded;
    boolean xgboostAnomalyLoaded;
    boolean lightgbmAlertLoaded;
    boolean catboostAnomalyLoaded;
    boolean oneClassSvmLoaded;
    boolean churnExtraTreesLoaded;
    boolean forecastRidgeLoaded;
    boolean forecastXGBoostLoaded;
    boolean personaEnabled;
    String personaSkippedReason;
    boolean llmExplanationInDataprocessor;
    boolean llmEvidencePayloadEnabled;
    List<String> missingArtifacts;
    List<String> warnings;
    Instant lastInferenceTimestamp;
    long inferenceErrorCount;
    String fallbackMode;
    Map<String, Object> fieldCoverage;
}
