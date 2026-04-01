package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/* DTO for user_risk_profile records. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRiskProfileDto {
    private Long id;
    private String insuredId;
    private Instant lastUpdated;
    private Integer anomalyCount7d;
    private Integer anomalyCount30d;
    private String lastAnomalyType;
    private String riskTier;
    private Double anomalyRate30d;
    private Integer sessions7d;
    private Integer sessions30d;
    private String mostFrequentAction30d;
    private Double avgSessionDuration30d;
    private Integer consecutiveCleanSessions;
}
