package com.noveocare.dataprocessor.dto;

import com.noveocare.dataprocessor.ai.sequence.SequenceFieldContribution;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregated insight produced after running inference on a live session.
 */
@Value
@Builder(toBuilder = true)
public class SessionInsight {
    /* Session and user identifiers. */
    String insuredId;
    String sessionId;
    Instant computedAt;

    /* Binary anomaly flags and scores. */
    boolean binaryAnomaly;
    boolean anomaly;
    Double anomalyScore;
    Double anomalyProbability;
    String binaryDetectorArtifact;
    String anomalyType;
    Double anomalyTypeConfidence;

    /* Churn and persona predictions. */
    Double churnProbability;
    Integer personaCluster;

    /* Ensemble risk aggregation outputs. */
    Double ensembleRiskScore;
    String riskLevel;
    String riskScale;

    /* Path deviation and next-action predictions. */
    PathDeviationResult pathDeviation;
    List<PathDeviationResult> rareTransitions;
    List<NextActionScore> nextActions;

    /* Contextual tags, rules, and warnings. */
    List<String> contextTags;
    List<String> triggeredRules;
    List<String> warnings;
    List<FeatureContribution> topContributingFeatures;
    String explainabilityText;

    /* Sequence model (Transformer / TCN) scores. */
    String sequenceModelPrimary;
    String sequenceModelFast;
    String selectedSequenceModel;
    String sequenceModelArtifact;
    Double transformerScore;
    Double transformerRiskScore100;
    Double tcnScore;
    Double tcnRiskScore100;
    Double sequenceAnomalyScore;
    Double sequenceCategoricalScore;
    Double sequenceContinuousScore;
    Double sequenceContextScore;
    Long sequenceLatencyMs;
    Boolean sequenceContextAvailable;
    Boolean sequenceRunBoth;
    java.util.List<String> sequenceActuallyRanModels;
    Boolean transformerUsedInFusion;
    Boolean tcnUsedInFusion;

    /* Composite risk scores from AI and rule engines. */
    Double aiRiskScore;
    Double ruleRiskScore;
    List<?> ruleContributions;
    Map<String, Object> ruleEvidence;
    Double finalRiskScore;
    List<SequenceFieldContribution> sequenceTopContributions;

    /* Anomaly type classification details. */
    String anomalyTypeSource;
    Map<String, Object> anomalyTypeEvidence;
    String personaLabel;
    String personaSource;
    Double personaConfidence;
    List<String> personaWarnings;

    /* Churn model outputs. */
    String churnRiskLevel;
    String churnModelName;
    String churnModelArtifact;
    List<String> churnFeatureWarnings;

    /* Forecast predictions for total events and anomaly rate. */
    Double forecastTotalEvents;
    Double forecastAnomalyRate;
    Double forecastExpectedAlertVolume;
    String forecastTotalEventsModel;
    String forecastAnomalyRateModel;
    Map<String, Object> forecastContext;

    /* Tabular (XGBoost, LightGBM, CatBoost, OneClassSVM) scores. */
    Double xgboostAnomalyScore;
    Double xgboostAnomalyScore100;
    String xgboostArtifact;
    Double lightgbmAlertScore;
    Double lightgbmAlertScore100;
    String lightgbmArtifact;
    Double catboostAnomalyScore;
    Double catboostAnomalyScore100;
    Double oneClassSvmNoveltyScoreRaw;
    Double oneClassSvmNoveltyScore100;
    List<String> availableTabularModels;
    List<String> unavailableTabularModels;
    List<String> tabularWarnings;

    /* Business context, fusion weights, and fallback. */
    Double businessContextScore;
    Double aggregationBoost;
    Map<String, Double> riskFusionWeights;
    Map<String, Double> unavailableModelWeights;
    Map<String, Object> modelScores;
    Map<String, Object> modelContributions;
    String fallbackMode;
    Map<String, Object> llmExplanationEvidencePayload;
    Map<String, Object> investigationPayload;
    Map<String, String> modelArtifacts;
}
