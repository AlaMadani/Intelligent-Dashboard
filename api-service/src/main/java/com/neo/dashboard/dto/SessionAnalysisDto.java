package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API projection for one analyzed session, including behavioral metrics,
 * anomaly classification, and parsed JSON aggregates.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionAnalysisDto {
    /* Identity and ownership fields. */
    private Long id;
    private String insuredId;
    private String sessionId;

    /* Time window and volume metrics for the session. */
    private Instant startTime;
    private Instant endTime;
    private Integer sessionLength;
    private Long sessionDurationSeconds;
    private Integer uniqueActionCount;
    private Double koRate;
    private Double meanDeltaSeconds;
    private Double actionDiversity;

    /* Parsed model inputs and outputs stored as JSON in SQL. */
    private Map<String, Long> actionCounts;
    private Double aeScore;
    @JsonProperty("isAnomaly")
    private Boolean isAnomaly;
    private String anomalyType;
    private Double typeConfidence;
    private List<String> top3NextActions;

    /* Rule-engine flags and ingestion timestamp. */
    private Boolean ruleTriggered;
    private String ruleType;
    private Instant createdAt;
}
