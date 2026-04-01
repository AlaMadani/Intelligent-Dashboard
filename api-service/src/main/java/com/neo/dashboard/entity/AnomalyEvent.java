package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/* Read-only JPA entity for anomaly_events. */
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

    @Column(name = "type_confidence")
    private Double typeConfidence;

    @Column(name = "rule_type")
    private String ruleType;

    @Column(name = "event_json", columnDefinition = "NVARCHAR(MAX)")
    private String eventJson;

    @Column(name = "detected_at")
    private Instant detectedAt;
}
