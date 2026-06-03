package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Read-only mapping of {@code anomaly_events} as written by the Data Processor.
 */
@Entity
@Table(name = "anomaly_events")
@Data
@Immutable
public class AnomalyEvent {

    @Id
    private Long id;

    @Column(name = "insured_id")
    private String insuredId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_time")
    private Instant eventTime;

    @Column(name = "anomaly_tier")
    private String anomalyTier;

    @Column(name = "anomaly_type")
    private String anomalyType;

    @Column(name = "anomaly_score")
    private Double anomalyScore;

    @Column(name = "anomaly_probability")
    private Double anomalyProbability;

    @Column(name = "type_confidence")
    private Double typeConfidence;

    @Column(name = "rule_type")
    private String ruleType;

    @Column(name = "anomaly_flag")
    private Boolean anomalyFlag;

    @Column(name = "churn_probability")
    private Double churnProbability;

    @Column(name = "risk_score")
    private Double riskScore;

    @Column(name = "persona_cluster")
    private Integer personaCluster;

    @Column(name = "persona_label")
    private String personaLabel;

    @Column(name = "ai_risk_score")
    private Double aiRiskScore;

    @Column(name = "rule_risk_score")
    private Double ruleRiskScore;

    @Column(name = "final_risk_score")
    private Double finalRiskScore;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "v36_runtime_version")
    private String v36RuntimeVersion;

    @Column(name = "xgboost_anomaly_score")
    private Double xgboostAnomalyScore;

    @Column(name = "xgboost_anomaly_score_100")
    private Double xgboostAnomalyScore100;

    @Column(name = "lightgbm_alert_score")
    private Double lightgbmAlertScore;

    @Column(name = "lightgbm_alert_score_100")
    private Double lightgbmAlertScore100;

    @Column(name = "transformer_risk_score_100")
    private Double transformerRiskScore100;

    @Column(name = "tcn_risk_score_100")
    private Double tcnRiskScore100;

    @Column(name = "triggered_rules_json", columnDefinition = "NVARCHAR(MAX)")
    private String triggeredRulesJson;

    @Column(name = "model_contributions_json", columnDefinition = "NVARCHAR(MAX)")
    private String modelContributionsJson;

    @Column(name = "anomaly_type_confidence")
    private Double anomalyTypeConfidence;

    @Column(name = "anomaly_type_source")
    private String anomalyTypeSource;

    @Column(name = "anomaly_type_evidence_json", columnDefinition = "NVARCHAR(MAX)")
    private String anomalyTypeEvidenceJson;

    @Column(name = "churn_risk_level")
    private String churnRiskLevel;

    @Column(name = "investigation_payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String investigationPayloadJson;

    @Column(name = "llm_explanation_evidence_payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String llmExplanationEvidencePayloadJson;

    @Column(name = "runtime_warnings_json", columnDefinition = "NVARCHAR(MAX)")
    private String runtimeWarningsJson;

    @Column(name = "event_json", columnDefinition = "NVARCHAR(MAX)")
    private String eventJson;

    @Column(name = "detected_at")
    private Instant detectedAt;
}