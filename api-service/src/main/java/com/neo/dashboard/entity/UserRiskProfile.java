package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/* Read-only JPA entity for user_risk_profile. */
@Entity
@Table(name = "user_risk_profile")
@Data
@Immutable
public class UserRiskProfile {
    @Id
    private Long id;

    @Column(name = "insured_id")
    private String insuredId;

    @Column(name = "last_updated")
    private Instant lastUpdated;

    @Column(name = "anomaly_count_7d")
    private Integer anomalyCount7d;

    @Column(name = "anomaly_count_30d")
    private Integer anomalyCount30d;

    @Column(name = "last_anomaly_type")
    private String lastAnomalyType;

    @Column(name = "risk_tier")
    private String riskTier;

    @Column(name = "anomaly_rate_30d")
    private Double anomalyRate30d;

    @Column(name = "sessions_7d")
    private Integer sessions7d;

    @Column(name = "sessions_30d")
    private Integer sessions30d;

    @Column(name = "most_frequent_action_30d")
    private String mostFrequentAction30d;

    @Column(name = "avg_session_duration_30d")
    private Double avgSessionDuration30d;

    @Column(name = "consecutive_clean_sessions")
    private Integer consecutiveCleanSessions;
}
