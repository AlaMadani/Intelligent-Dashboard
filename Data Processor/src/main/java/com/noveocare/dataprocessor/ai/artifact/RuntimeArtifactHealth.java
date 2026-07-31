package com.noveocare.dataprocessor.ai.artifact;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Immutable snapshot of runtime artifact availability, listing present models
 * and any missing artifacts or warnings.
 */
@Value
@Builder(toBuilder = true)
public class RuntimeArtifactHealth {
    String runtimeVersion;
    String artifactBasePath;
    boolean transformerArtifactPresent;
    boolean tcnArtifactPresent;
    boolean xgboostAnomalyArtifactPresent;
    boolean lightgbmAlertArtifactPresent;
    boolean catboostAnomalyArtifactPresent;
    boolean oneClassSvmArtifactPresent;
    boolean churnExtraTreesArtifactPresent;
    boolean forecastRidgeArtifactPresent;
    boolean forecastXGBoostArtifactPresent;
    boolean personaEnabled;
    String personaSkippedReason;
    boolean llmExplanationInDataprocessor;
    boolean llmEvidencePayloadEnabled;
    List<String> missingArtifacts;
    List<String> warnings;
}
