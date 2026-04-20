package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Typed projection of the live session insight blob stored in Redis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActiveSessionDto {

    private String sessionId;
    private String insuredId;
    private String persona;
    private String countryCode;
    private String city;

    private Instant sessionStart;
    private Instant sessionEnd;
    private Integer totalEvents;
    private Long totalDurationSeconds;

    private String firstAction;
    private String lastAction;
    private String firstRoute;
    private String lastRoute;

    private Boolean binaryAnomaly;
    private Boolean anomalyFlag;
    private String anomalyType;
    private Double anomalyScore;
    private Double anomalyProbability;
    private Double churnProbability;
    private Double riskScore;
    private Integer personaCluster;
    private String riskLevel;

    private PathDeviationDto pathDeviation;
    private List<PathDeviationDto> rareTransitions;
    private List<NextActionScoreDto> nextActions;
    private List<String> contextTags;
    private List<String> triggeredRules;
    private List<String> warnings;
    private List<FeatureContributionDto> topContributingFeatures;
    private String explainabilityText;
    private List<String> actionSequence;
    private List<String> routeSequence;

    private Instant computedAt;
}
