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
 * Captures every detected anomaly with its associated scores, model outputs,
 * and contextual evidence for a single event within an insured session.
 */
@Entity
@Table(name = "anomaly_events")
@Data
@Immutable
public class AnomalyEvent {

    /** Unique identifier for the anomaly event record. */
    @Id
    private Long id;

    /** External identifier for the insured individual associated with this event. */
    @Column(name = "insured_id")
    private String insuredId;

    /** Identifier of the browsing session during which the anomaly occurred. */
    @Column(name = "session_id")
    private String sessionId;

    /** Unique identifier of the raw event that triggered the anomaly detection. */
    @Column(name = "event_id")
    private String eventId;

    /** Timestamp when the original event was recorded by the tracking system. */
    @Column(name = "event_time")
    private Instant eventTime;

    /** Severity tier assigned to the anomaly (e.g. LOW, MEDIUM, HIGH, CRITICAL). */
    @Column(name = "anomaly_tier")
    private String anomalyTier;

    /** Categorisation of the anomaly type (e.g. velocity, geolocation, device). */
    @Column(name = "anomaly_type")
    private String anomalyType;

    /** Raw anomaly score produced by the primary detection model; higher is more anomalous. */
    @Column(name = "anomaly_score")
    private Double anomalyScore;

    /** Probability (0-1) that the event is genuinely anomalous. */
    @Column(name = "anomaly_probability")
    private Double anomalyProbability;

    /** Confidence level in the assigned anomaly type classification. */
    @Column(name = "type_confidence")
    private Double typeConfidence;

    /** Name of the rule or rule-set that flagged the anomaly (if rule-based). */
    @Column(name = "rule_type")
    private String ruleType;

    /** Boolean flag indicating whether the event was ultimately marked as anomalous. */
    @Column(name = "anomaly_flag")
    private Boolean anomalyFlag;

    /** Predicted probability that the insured will churn, derived from this event's context. */
    @Column(name = "churn_probability")
    private Double churnProbability;

    /** Overall risk score for the insured at the time of this event. */
    @Column(name = "risk_score")
    private Double riskScore;

    /** Cluster number assigned by the persona segmentation model. */
    @Column(name = "persona_cluster")
    private Integer personaCluster;

    /** Human-readable label for the assigned persona cluster. */
    @Column(name = "persona_label")
    private String personaLabel;

    /** Risk score produced by the AI/ML ensemble models. */
    @Column(name = "ai_risk_score")
    private Double aiRiskScore;

    /** Risk score produced by the rule-based engine. */
    @Column(name = "rule_risk_score")
    private Double ruleRiskScore;

    /** Blended risk score combining AI and rule-based outputs. */
    @Column(name = "final_risk_score")
    private Double finalRiskScore;

    /** Categorical level of the final risk (e.g. LOW, MEDIUM, HIGH). */
    @Column(name = "risk_level")
    private String riskLevel;

    /** Version string of the V3.6 runtime that processed this event. */
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

    /** Transformer model risk score scaled to a 0-100 range. */
    @Column(name = "transformer_risk_score_100")
    private Double transformerRiskScore100;

    /** Temporal Convolutional Network risk score scaled to a 0-100 range. */
    @Column(name = "tcn_risk_score_100")
    private Double tcnRiskScore100;

    /** JSON payload listing the rules that triggered during this event's evaluation. */
    @Column(name = "triggered_rules_json", columnDefinition = "NVARCHAR(MAX)")
    private String triggeredRulesJson;

    /** JSON payload detailing how each model contributed to the final score. */
    @Column(name = "model_contributions_json", columnDefinition = "NVARCHAR(MAX)")
    private String modelContributionsJson;

    /** Confidence score for the secondary anomaly-type classification logic. */
    @Column(name = "anomaly_type_confidence")
    private Double anomalyTypeConfidence;

    /** Source system or method that produced the anomaly type classification. */
    @Column(name = "anomaly_type_source")
    private String anomalyTypeSource;

    /** JSON evidence payload supporting the anomaly type classification. */
    @Column(name = "anomaly_type_evidence_json", columnDefinition = "NVARCHAR(MAX)")
    private String anomalyTypeEvidenceJson;

    /** Categorical level of churn risk derived from all signals (LOW, MEDIUM, HIGH). */
    @Column(name = "churn_risk_level")
    private String churnRiskLevel;

    /** JSON payload with data collected for manual fraud investigation workflows. */
    @Column(name = "investigation_payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String investigationPayloadJson;

    /** JSON payload containing the evidence used to generate LLM-based explanations. */
    @Column(name = "llm_explanation_evidence_payload_json", columnDefinition = "NVARCHAR(MAX)")
    private String llmExplanationEvidencePayloadJson;

    /** JSON array of warnings emitted during runtime processing. */
    @Column(name = "runtime_warnings_json", columnDefinition = "NVARCHAR(MAX)")
    private String runtimeWarningsJson;

    /** Complete raw event payload as received by the processing pipeline. */
    @Column(name = "event_json", columnDefinition = "NVARCHAR(MAX)")
    private String eventJson;

    /** Timestamp when the anomaly was detected by the pipeline. */
    @Column(name = "detected_at")
    private Instant detectedAt;
}
