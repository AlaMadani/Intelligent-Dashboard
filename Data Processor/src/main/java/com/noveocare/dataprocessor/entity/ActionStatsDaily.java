package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily per-action history used both as training/inference context and for spike reporting.
 */
@Entity
@Table(name = "action_stats_daily", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"stat_date", "action_id"})
})
@Data
public class ActionStatsDaily {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Natural key identifying the action and day represented by this row.
    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "action_id", nullable = false)
    private Integer actionId;

    @Column(name = "action_label")
    private String actionLabel;

    @Column(name = "actual_count")
    private Long actualCount;

    // Prediction outputs and rolling baseline used for spike detection.
    @Column(name = "predicted_count")
    private Double predictedCount;

    @Column(name = "rolling_mean_7")
    private Double rollingMean7;

    @Column(name = "rolling_std_7")
    private Double rollingStd7;

    @Column(name = "spike_alert")
    private Boolean spikeAlert;

    @Column(name = "created_at")
    private Instant createdAt;
}
