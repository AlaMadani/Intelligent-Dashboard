package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * API projection for a persisted anomaly event.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyEventDto {

    private Long id;
    private String insuredId;
    private String sessionId;
    private String eventId;

    private Instant eventTime;
    private String anomalyTier;
    private String anomalyType;
    private Double anomalyScore;
    private Double anomalyProbability;
    private Double typeConfidence;
    private String ruleType;
    private Boolean anomalyFlag;
    private Double churnProbability;
    private Double riskScore;
    private Integer personaCluster;
    private Boolean pathDeviation;
    private Double transitionProbability;
    private String transitionFromAction;
    private String transitionToAction;
    private String modelArtifact;
    private List<String> nextActions;

    private String eventJson;
    private Instant detectedAt;
}
