package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * DTO for daily action volume aggregates that feed the live and trend stats
 * widgets.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActionStatsDailyDto {
    /* Aggregate identity and the day being summarized. */
    private Long id;
    private LocalDate statDate;

    /* Action identifier and human-readable label. */
    private Integer actionId;
    private String actionLabel;

    /* Observed vs predicted activity metrics. */
    private Long actualCount;
    private Double predictedCount;
    private Double rollingMean7;
    private Double rollingStd7;
    private Boolean spikeAlert;

    /* Timestamp for the snapshot row itself. */
    private Instant createdAt;
}
