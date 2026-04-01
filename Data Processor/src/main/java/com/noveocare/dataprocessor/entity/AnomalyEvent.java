package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "anomaly_events")
@Data
public class AnomalyEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "anomaly_type")
    private String anomalyType;

    @Column(name = "anomaly_score")
    private Double anomalyScore;

    @Column(name = "type_confidence")
    private Double typeConfidence;

    @Column(name = "rule_type")
    private String ruleType;

    @Column(name = "event_json", columnDefinition = "NVARCHAR(MAX)")
    private String eventJson;

    @Column(name = "detected_at")
    private Instant detectedAt;
}
