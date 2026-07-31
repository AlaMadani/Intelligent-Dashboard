package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Read-only mapping of {@code session_analysis} as produced by the Data Processor
 * V3.4 sequence runtime and session aggregation pipeline.
 * Contains per-session aggregated metrics, model scores, persona assignments,
 * churn predictions, and risk assessments computed from the full event sequence.
 */
@Entity
@Table(name = "session_analysis")
@Data
@Immutable
public class SessionAnalysis {

    /** Unique identifier for the session analysis record. */
    @Id
    private Long id;

    /** External identifier of the insured individual whose session was analysed. */
    @Column(name = "insured_id")
    private String insuredId;

    /** Identifier of the session that was analysed. */
    @Column(name = "session_id")
    private String sessionId;

    /** ISO country code associated with the insured's region. */
    @Column(name = "country_code")
    private String countryCode;

    /** Timestamp of the first event in the session. */
    @Column(name = "start_time")
    private Instant startTime;

    /** Timestamp of the last event in the session. */
    @Column(name = "end_time")
    private Instant endTime;

    /** Total number of events that occurred during the session. */
    @Column(name = "session_length")
    private Integer totalEvents;

    /** Total duration of the session in seconds, from first to last event. */
    @Column(name = "session_duration_seconds")
    private Long sessionDurationSeconds;

    /** Count of distinct action types observed in the session. */
    @Column(name = "unique_action_count")
    private Integer uniqueActions;

    /** JSON array of the chronological action sequence for the session. */
    @Column(name = "action_sequence_json", columnDefinition = "NVARCHAR(MAX)")
    private String actionSequenceJson;

    /** JSON array of the chronological route/page sequence visited. */
    @Column(name = "route_sequence_json", columnDefinition = "NVARCHAR(MAX)")
    private String routeSequenceJson;

    /** JSON object mapping each action type to its occurrence count. */
    @Column(name = "action_counts_json", columnDefinition = "NVARCHAR(MAX)")
    private String actionCountsJson;

    /** Probability (0-1) that the insured will churn, derived from session behaviour. */
    @Column(name = "churn_probability")
    private Double churnProbability;

    /** Cluster number assigned by the persona segmentation model for this session. */
    @Column(name = "persona_cluster")
    private Integer personaCluster;

    /** Artifact identifier of the sequence model used to score this session. */
    @Column(name = "sequence_model_artifact")
    private String sequenceModelArtifact;

    /** Overall anomaly score from the sequence models. */
    @Column(name = "sequence_anomaly_score")
    private Double sequenceAnomalyScore;

    /** Categorical sub-score component of the sequence anomaly evaluation. */
    @Column(name = "sequence_cat_score")
    private Double sequenceCatScore;

    /** Continuous sub-score component of the sequence anomaly evaluation. */
    @Column(name = "sequence_cont_score")
    private Double sequenceContScore;

    /** Contextual sub-score component of the sequence anomaly evaluation. */
    @Column(name = "sequence_ctx_score")
    private Double sequenceCtxScore;

    /** Risk score produced by the AI/ML ensemble models. */
    @Column(name = "ai_risk_score")
    private Double aiRiskScore;

    /** Risk score produced by the rule-based engine. */
    @Column(name = "rule_risk_score")
    private Double ruleRiskScore;

    /** Blended risk score combining AI and rule-based outputs. */
    @Column(name = "final_risk_score")
    private Double finalRiskScore;

    /** Version string of the V3.6 runtime that processed this session. */
    @Column(name = "v36_runtime_version")
    private String v36RuntimeVersion;

    /** Raw anomaly score produced by the XGBoost model. */
    @Column(name = "xgboost_anomaly_score")
    private Double xgboostAnomalyScore;

    /** XGBoost anomaly score scaled to a 0-100 range for consistent display. */
    @Column(name = "xgboost_anomaly_score_100")
    private Double xgboostAnomalyScore100;

    /** Alert score produced by the LightGBM model. */
    @Column(name = "lightgbm_alert_score")
    private Double lightgbmAlertScore;

    /** LightGBM alert score scaled to a 0-100 range. */
    @Column(name = "lightgbm_alert_score_100")
    private Double lightgbmAlertScore100;

    /** Raw anomaly score produced by the CatBoost model. */
    @Column(name = "catboost_anomaly_score")
    private Double catboostAnomalyScore;

    /** CatBoost anomaly score scaled to a 0-100 range. */
    @Column(name = "catboost_anomaly_score_100")
    private Double catboostAnomalyScore100;

    /** Raw novelty score from the One-Class SVM model. */
    @Column(name = "oneclasssvm_novelty_score")
    private Double oneclasssvmNoveltyScore;

    /** One-Class SVM novelty score scaled to a 0-100 range. */
    @Column(name = "oneclasssvm_novelty_score_100")
    private Double oneclasssvmNoveltyScore100;

    /** Surprise score from the Transformer sequence model. */
    @Column(name = "transformer_surprise_score")
    private Double transformerSurpriseScore;

    /** Transformer model risk score scaled to a 0-100 range. */
    @Column(name = "transformer_risk_score_100")
    private Double transformerRiskScore100;

    /** Artifact identifier of the Transformer model used. */
    @Column(name = "transformer_artifact")
    private String transformerArtifact;

    /** Indicates whether processing fell back to a simpler model (reason/code). */
    @Column(name = "fallback_mode")
    private String fallbackMode;

    /** Surprise score from the Temporal Convolutional Network model. */
    @Column(name = "tcn_surprise_score")
    private Double tcnSurpriseScore;

    /** TCN risk score scaled to a 0-100 range. */
    @Column(name = "tcn_risk_score_100")
    private Double tcnRiskScore100;

    /** Artifact identifier of the TCN model used. */
    @Column(name = "tcn_artifact")
    private String tcnArtifact;

    /** Human-readable label for the assigned persona cluster. */
    @Column(name = "persona_label")
    private String personaLabel;

    /** Source system or method that produced the persona assignment. */
    @Column(name = "persona_source")
    private String personaSource;

    /** Confidence score for the persona cluster assignment. */
    @Column(name = "persona_confidence")
    private Double personaConfidence;

    /** Categorical level of churn risk (LOW, MEDIUM, HIGH). */
    @Column(name = "churn_risk_level")
    private String churnRiskLevel;

    /** Name of the model used for churn prediction. */
    @Column(name = "churn_model_name")
    private String churnModelName;

    /** Artifact identifier of the churn prediction model. */
    @Column(name = "churn_model_artifact")
    private String churnModelArtifact;

    /** JSON payload with warnings about feature quality for churn prediction. */
    @Column(name = "churn_feature_warnings_json", columnDefinition = "NVARCHAR(MAX)")
    private String churnFeatureWarningsJson;

    /** JSON object containing references to all model artifacts used in scoring. */
    @Column(name = "model_artifacts_json", columnDefinition = "NVARCHAR(MAX)")
    private String modelArtifactsJson;

    /** Source system or method that produced the anomaly type classification. */
    @Column(name = "anomaly_type_source")
    private String anomalyTypeSource;

    /** Confidence score for the anomaly type classification. */
    @Column(name = "anomaly_type_confidence")
    private Double anomalyTypeConfidence;

    /** JSON evidence payload supporting the anomaly type classification. */
    @Column(name = "anomaly_type_evidence_json", columnDefinition = "NVARCHAR(MAX)")
    private String anomalyTypeEvidenceJson;

    /** JSON array of warnings generated during session processing. */
    @Column(name = "warnings_json", columnDefinition = "NVARCHAR(MAX)")
    private String warningsJson;

    /** JSON payload listing the rules that triggered during session evaluation. */
    @Column(name = "triggered_rules_json", columnDefinition = "NVARCHAR(MAX)")
    private String triggeredRulesJson;

    /** JSON payload detailing individual rule contributions to the final score. */
    @Column(name = "rule_contributions_json", columnDefinition = "NVARCHAR(MAX)")
    private String ruleContributionsJson;

    /** Business-contextual risk modifier applied to the aggregated score. */
    @Column(name = "business_context_score")
    private Double businessContextScore;

    /** Aggregation boost factor applied to session-level scores. */
    @Column(name = "aggregation_boost")
    private Double aggregationBoost;

    /** JSON payload detailing how each model contributed to the final score. */
    @Column(name = "model_contributions_json", columnDefinition = "NVARCHAR(MAX)")
    private String modelContributionsJson;

    /** Categorical level of the final risk (LOW, MEDIUM, HIGH). */
    @Column(name = "risk_level")
    private String riskLevel;

    /** JSON array of the fields that contributed most to the sequence surprise score. */
    @Column(name = "top_sequence_surprise_fields_json", columnDefinition = "NVARCHAR(MAX)")
    private String topSequenceSurpriseFieldsJson;

    /** Name of the model selected as the best-performing sequence model. */
    @Column(name = "selected_sequence_model")
    private String selectedSequenceModel;

    /** Name of the model used to forecast total session events. */
    @Column(name = "forecast_total_events_model")
    private String forecastTotalEventsModel;

    /** Name of the model used to forecast the anomaly rate. */
    @Column(name = "forecast_anomaly_rate_model")
    private String forecastAnomalyRateModel;

    /** JSON payload with context data used for forecasting. */
    @Column(name = "forecast_context_json", columnDefinition = "NVARCHAR(MAX)")
    private String forecastContextJson;

    /** JSON payload containing the evidence used to generate LLM-based explanations. */
    @Column(name = "llm_explanation_evidence_payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String llmExplanationEvidencePayloadJson;

    /** JSON array of the features that most heavily influenced the final score. */
    @Column(name = "top_contributing_features_json", columnDefinition = "NVARCHAR(MAX)")
    private String topContributingFeaturesJson;

    /** JSON payload with data collected for manual investigation workflows. */
    @Column(name = "investigation_payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String investigationPayloadJson;

    /** Timestamp when the session analysis record was created. */
    @Column(name = "created_at")
    private Instant createdAt;
}
