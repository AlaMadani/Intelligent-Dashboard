package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Read-only mapping of the `action_stats_daily` aggregate table used by the
 * dashboard stats views.
 */
@Entity
@Table(name = "action_stats_daily")
@Data
@Immutable
public class ActionStatsDaily {
    /* Row identity and the business date being summarized. */
    @Id
    private Long id;

    @Column(name = "stat_date")
    private LocalDate statDate;

    @Column(name = "action_id")
    private Integer actionId;

    @Column(name = "action_label")
    private String actionLabel;

    /* Observed and predicted activity statistics for the action. */
    @Column(name = "actual_count")
    private Long actualCount;

    @Column(name = "predicted_count")
    private Double predictedCount;

    @Column(name = "rolling_mean_7")
    private Double rollingMean7;

    @Column(name = "rolling_std_7")
    private Double rollingStd7;

    @Column(name = "spike_alert")
    private Boolean spikeAlert;

    /* Persistence timestamp for the aggregate snapshot. */
    @Column(name = "created_at")
    private Instant createdAt;
}
