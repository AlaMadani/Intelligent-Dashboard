package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "session_analysis")
@Data
public class SessionAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "insured_id", nullable = false)
    private String insuredId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "session_length")
    private Integer totalEvents;

    @Column(name = "session_duration_seconds")
    private Long sessionDurationSeconds;

    @Column(name = "unique_action_count")
    private Integer uniqueActions;

    @Column(name = "action_sequence_json", columnDefinition = "NVARCHAR(MAX)")
    private String actionSequenceJson;

    @Column(name = "route_sequence_json", columnDefinition = "NVARCHAR(MAX)")
    private String routeSequenceJson;

    @Column(name = "action_counts_json", columnDefinition = "NVARCHAR(MAX)")
    private String actionCountsJson;

    @Column(name = "churn_probability")
    private Double churnProbability;

    @Column(name = "persona_cluster")
    private Integer personaCluster;

    @Column(name = "v36_runtime_version", columnDefinition = "NVARCHAR(32)")
    private String v36RuntimeVersion;

    @Column(name = "xgboost_anomaly_score")
    private Double xgboostAnomalyScore;

    @Column(name = "xgboost_anomaly_score_100")
    private Double xgboostAnomalyScore100;

    @Column(name = "lightgbm_alert_score")
    private Double lightgbmAlertScore;

    @Column(name = "lightgbm_alert_score_100")
    private Double lightgbmAlertScore100;

    @Column(name = "catboost_anomaly_score")
    private Double catboostAnomalyScore;

    @Column(name = "catboost_anomaly_score_100")
    private Double catboostAnomalyScore100;

    @Column(name = "oneclasssvm_novelty_score")
    private Double oneClassSvmNoveltyScore;

    @Column(name = "oneclasssvm_novelty_score_100")
    private Double oneClassSvmNoveltyScore100;

    @Column(name = "sequence_model_artifact")
    private String sequenceModelArtifact;

    @Column(name = "selected_sequence_model", columnDefinition = "NVARCHAR(32)")
    private String selectedSequenceModel;

    @Column(name = "sequence_anomaly_score")
    private Double sequenceAnomalyScore;

    @Column(name = "sequence_cat_score")
    private Double sequenceCatScore;

    @Column(name = "sequence_cont_score")
    private Double sequenceContScore;

    @Column(name = "sequence_ctx_score")
    private Double sequenceCtxScore;

    @Column(name = "ai_risk_score")
    private Double aiRiskScore;

    @Column(name = "rule_risk_score")
    private Double ruleRiskScore;

    @Column(name = "final_risk_score")
    private Double finalRiskScore;

    @Column(name = "transformer_surprise_score")
    private Double transformerSurpriseScore;

    @Column(name = "transformer_risk_score_100")
    private Double transformerRiskScore100;

    @Column(name = "transformer_artifact", columnDefinition = "NVARCHAR(128)")
    private String transformerArtifact;

    @Column(name = "fallback_mode", columnDefinition = "NVARCHAR(32)")
    private String fallbackMode;

    @Column(name = "tcn_surprise_score")
    private Double tcnSurpriseScore;

    @Column(name = "tcn_risk_score_100")
    private Double tcnRiskScore100;

    @Column(name = "tcn_artifact", columnDefinition = "NVARCHAR(128)")
    private String tcnArtifact;

    @Column(name = "top_sequence_surprise_fields_json", columnDefinition = "NVARCHAR(MAX)")
    private String topSequenceSurpriseFieldsJson;

    @Column(name = "rule_contributions_json", columnDefinition = "NVARCHAR(MAX)")
    private String ruleContributionsJson;

    @Column(name = "business_context_score")
    private Double businessContextScore;

    @Column(name = "aggregation_boost")
    private Double aggregationBoost;

    @Column(name = "model_contributions_json", columnDefinition = "NVARCHAR(MAX)")
    private String modelContributionsJson;

    @Column(name = "risk_level", columnDefinition = "NVARCHAR(32)")
    private String riskLevel;

    @Column(name = "anomaly_type_source", columnDefinition = "NVARCHAR(64)")
    private String anomalyTypeSource;

    @Column(name = "anomaly_type_confidence")
    private Double anomalyTypeConfidence;

    @Column(name = "anomaly_type_evidence_json", columnDefinition = "NVARCHAR(MAX)")
    private String anomalyTypeEvidenceJson;

    @Column(name = "persona_label")
    private String personaLabel;

    @Column(name = "persona_source")
    private String personaSource;

    @Column(name = "persona_confidence")
    private Double personaConfidence;

    @Column(name = "churn_risk_level")
    private String churnRiskLevel;

    @Column(name = "churn_model_name", columnDefinition = "NVARCHAR(128)")
    private String churnModelName;

    @Column(name = "churn_model_artifact", columnDefinition = "NVARCHAR(128)")
    private String churnModelArtifact;

    @Column(name = "churn_feature_warnings_json", columnDefinition = "NVARCHAR(MAX)")
    private String churnFeatureWarningsJson;

    @Column(name = "forecast_total_events_model", columnDefinition = "NVARCHAR(64)")
    private String forecastTotalEventsModel;

    @Column(name = "forecast_anomaly_rate_model", columnDefinition = "NVARCHAR(64)")
    private String forecastAnomalyRateModel;

    @Column(name = "forecast_context_json", columnDefinition = "NVARCHAR(MAX)")
    private String forecastContextJson;

    @Column(name = "llm_explanation_evidence_payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String llmExplanationEvidencePayloadJson;

    @Column(name = "top_contributing_features_json", columnDefinition = "NVARCHAR(MAX)")
    private String topContributingFeaturesJson;

    @Column(name = "investigation_payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String investigationPayloadJson;

    @Column(name = "model_artifacts_json", columnDefinition = "NVARCHAR(MAX)")
    private String modelArtifactsJson;

    @Column(name = "warnings_json", columnDefinition = "NVARCHAR(MAX)")
    private String warningsJson;

    @Column(name = "triggered_rules_json", columnDefinition = "NVARCHAR(MAX)")
    private String triggeredRulesJson;

    @Column(name = "created_at")
    private Instant createdAt;
}