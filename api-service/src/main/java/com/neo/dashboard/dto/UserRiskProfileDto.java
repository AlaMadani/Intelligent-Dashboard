package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * API projection of the insured user's recent risk posture and anomaly
 * history.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRiskProfileDto {
    /* Identity and freshness metadata for the snapshot. */
    private Long id;
    private String insuredId;
    private Instant lastUpdated;

    /* Short-term and medium-term anomaly counts. */
    private Integer anomalyCount7d;
    private Integer anomalyCount30d;
    private String lastAnomalyType;
    private String riskTier;
    private Double anomalyRate30d;

    /* Activity volume and baseline behavior indicators. */
    private Integer sessions7d;
    private Integer sessions30d;
    private String mostFrequentAction30d;
    private Double avgSessionDuration30d;
    private Integer consecutiveCleanSessions;
}
