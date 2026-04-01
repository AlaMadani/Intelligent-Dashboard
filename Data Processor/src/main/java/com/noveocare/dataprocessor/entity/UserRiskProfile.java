package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "user_risk_profile", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"insured_id"})
})
@Data
public class UserRiskProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "insured_id", nullable = false)
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
