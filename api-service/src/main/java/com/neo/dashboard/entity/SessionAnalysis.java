package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/* Read-only JPA entity for session_analysis. */
@Entity
@Table(name = "session_analysis")
@Data
@Immutable
public class SessionAnalysis {
    @Id
    private Long id;

    @Column(name = "insured_id")
    private String insuredId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "session_length")
    private Integer sessionLength;

    @Column(name = "session_duration_seconds")
    private Long sessionDurationSeconds;

    @Column(name = "unique_action_count")
    private Integer uniqueActionCount;

    @Column(name = "ko_rate")
    private Double koRate;

    @Column(name = "mean_delta_seconds")
    private Double meanDeltaSeconds;

    @Column(name = "action_diversity")
    private Double actionDiversity;

    @Column(name = "action_counts_json", columnDefinition = "NVARCHAR(MAX)")
    private String actionCountsJson;

    @Column(name = "ae_score")
    private Double aeScore;

    @Column(name = "is_anomaly")
    private Boolean isAnomaly;

    @Column(name = "anomaly_type")
    private String anomalyType;

    @Column(name = "type_confidence")
    private Double typeConfidence;

    @Column(name = "top3_next_actions", columnDefinition = "NVARCHAR(MAX)")
    private String top3NextActions;

    @Column(name = "rule_triggered")
    private Boolean ruleTriggered;

    @Column(name = "rule_type", columnDefinition = "NVARCHAR(MAX)")
    private String ruleType;

    @Column(name = "created_at")
    private Instant createdAt;
}
