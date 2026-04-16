package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Durable anomaly-event record written whenever the pipeline emits an alert.
 */
@Entity
@Table(name = "anomaly_events")
@Data
public class AnomalyEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Source identifiers copied from the alert payload.
    @Column(name = "insured_id", nullable = false)
    private String insuredId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_time")
    private Instant eventTime;

    @Column(name = "anomaly_tier")
    private String anomalyTier;

    // Detection metadata kept for investigation and downstream reporting.
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

    @Column(name = "path_deviation")
    private Boolean pathDeviation;

    @Column(name = "transition_probability")
    private Double transitionProbability;

    @Column(name = "transition_from_action", columnDefinition = "NVARCHAR(512)")
    private String transitionFromAction;

    @Column(name = "transition_to_action", columnDefinition = "NVARCHAR(512)")
    private String transitionToAction;

    @Column(name = "model_artifact", columnDefinition = "NVARCHAR(128)")
    private String modelArtifact;

    @Column(name = "next_actions_json", columnDefinition = "NVARCHAR(MAX)")
    private String nextActionsJson;

    @Column(name = "event_json", columnDefinition = "NVARCHAR(MAX)")
    private String eventJson;

    // Time at which the processor decided to surface the anomaly.
    @Column(name = "detected_at")
    private Instant detectedAt;
}
