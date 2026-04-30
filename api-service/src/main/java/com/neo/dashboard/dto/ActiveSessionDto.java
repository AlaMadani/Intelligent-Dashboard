package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    private String month;
    private Integer sessionNumber;

    private Instant sessionStart;
    private Instant sessionEnd;
    private Integer totalEvents;
    private Long totalDurationSeconds;

    private String firstAction;
    private String lastAction;
    private String firstRoute;
    private String lastRoute;
    private Double avgInterActionSeconds;
    private Double minInterActionSeconds;
    private Double maxInterActionSeconds;
    private Integer uniqueActions;
    private Integer uniqueRoutes;
    private Integer uniqueIpsUsed;
    private Integer uniqueDevicesUsed;
    private Integer totalKOs;
    private Integer totalOKs;
    private Integer longestKoStreak;
    private Boolean hasLogin;
    private Boolean hasLogout;
    private Boolean ipChanged;
    private Boolean deviceChanged;
    private Integer totalDownloadActions;
    private Integer maxDownloadsIn2Minutes;
    private Integer pingPongCount;
    private Double riskScoreMax;
    private Double riskScoreAvg;
    private Integer anomalyEventCount;
    private List<String> anomalyTypes;
    private List<String> campaignIds;
    private Map<String, Long> actionCounts;
    private String actionSequenceSignature;
    private String routeSequenceSignature;

    private Boolean binaryAnomaly;
    private Boolean anomalyFlag;
    private String anomalyType;
    private Double anomalyScore;
    private Double anomalyProbability;
    private String binaryDetectorArtifact;
    @JsonAlias("anomalyTypeConfidence")
    private Double typeConfidence;
    private Double churnProbability;
    private Double riskScore;
    private Integer personaCluster;
    private String riskLevel;

    private PathDeviationDto pathDeviation;
    private Boolean pathDeviationFlag;
    private Double transitionProbability;
    private String transitionFromAction;
    private String transitionToAction;
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
