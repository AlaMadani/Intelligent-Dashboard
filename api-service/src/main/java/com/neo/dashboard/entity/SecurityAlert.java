package com.neo.dashboard.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/* JPA entity representing a detected security alert. */
@Entity
@Table(name = "security_alerts")
@Data
public class SecurityAlert {
    @Id
    private Long id;

    @Column(name = "user_key")
    private Integer userKey;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "alert_type")
    private String alertType;

    @Column(name = "anomaly_score")
    private Double anomalyScore;

    @Column(name = "threshold_used")
    private Double thresholdUsed;

    @Column(name = "ai_explanation", columnDefinition = "TEXT")
    private String aiExplanation;

    @Column(name = "detected_at")
    private Instant detectedAt;
}
