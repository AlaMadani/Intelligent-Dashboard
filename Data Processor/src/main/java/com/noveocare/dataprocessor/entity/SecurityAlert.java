package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Entity
@Table(name = "security_alerts")
@Data
public class SecurityAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_key")
    private Integer userKey;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "alert_type")
    private String alertType;

    // Model reconstruction error (used for anomaly detection).
    @Column(name = "anomaly_score")
    private Double anomalyScore;

    // Threshold applied when the alert was triggered.
    @Column(name = "threshold_used")
    private Double thresholdUsed;

    // Long-form explanation; stored as TEXT to avoid truncation.
    @Column(name = "ai_explanation", columnDefinition = "TEXT")
    private String aiExplanation;

    // UTC timestamp for when the anomaly was detected.
    @Column(name = "detected_at")
    private Instant detectedAt;
}
