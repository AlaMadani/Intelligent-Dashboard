package com.neo.dashboard.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/* JPA entity storing the sequence and prediction data for an alert. */
@Entity
@Table(name = "security_alert_sequences")
@Data
public class SecurityAlertSequence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_id", nullable = false, unique = true)
    private SecurityAlert alert;

    @Column(name = "user_key")
    private Integer userKey;

    @Column(name = "sequence_json", columnDefinition = "NVARCHAR(MAX)")
    private String sequenceJson;

    @Column(name = "prediction_json", columnDefinition = "NVARCHAR(MAX)")
    private String predictionJson;

    @Column(name = "prediction_summary", columnDefinition = "NVARCHAR(MAX)")
    private String predictionSummary;

    @Column(name = "created_at")
    private Instant createdAt;
}
