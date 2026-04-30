package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * DTO aligned with {@code AnomalyAlert} JSON from the Data Processor (Kafka and Redis).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnomalyAlertDto {

    private String insuredId;
    private String sessionId;
    private String eventId;

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
    private List<NextActionScoreDto> nextActions;

    private Instant eventTime;
    private Instant detectedAt;
}
