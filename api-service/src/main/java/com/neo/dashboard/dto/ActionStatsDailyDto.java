package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/* DTO for action_stats_daily records. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActionStatsDailyDto {
    private Long id;
    private LocalDate statDate;
    private Integer actionId;
    private String actionLabel;
    private Long actualCount;
    private Double predictedCount;
    private Double rollingMean7;
    private Double rollingStd7;
    private Boolean spikeAlert;
    private Instant createdAt;
}
