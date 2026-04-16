package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API projection for one analyzed session (SQL snapshot after session end).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionAnalysisDto {

    private Long id;
    private String insuredId;
    private String sessionId;

    private String persona;
    private String countryCode;
    private String city;
    private String month;
    private Integer sessionNumber;

    private Instant startTime;
    private Instant endTime;

    private String firstAction;
    private String lastAction;
    private String firstRoute;
    private String lastRoute;

    private Integer totalEvents;
    private Long sessionDurationSeconds;

    private Integer uniqueActions;
    private Integer uniqueRoutes;
    private Integer uniqueIpsUsed;
    private Integer uniqueDevicesUsed;

    private Integer totalKOs;
    private Integer totalOKs;
    private Integer longestKoStreak;

    private Double koRate;
    private Double avgInterActionSeconds;
    private Double minInterActionSeconds;
    private Double maxInterActionSeconds;
    private Double actionDiversity;

    private Boolean hasLogin;
    private Boolean hasLogout;
    private Boolean ipChanged;
    private Boolean deviceChanged;

    private Integer totalDownloadActions;
    private Integer maxDownloadsIn2Minutes;
    private Integer pingPongCount;

    private Double riskScoreMax;
    private Double riskScoreAvg;
    private Boolean endedAbruptly;
    private Integer anomalyEventCount;

    private List<String> anomalyTypes;
    private List<String> campaignIds;

    private String actionSequenceSignature;
    private String routeSequenceSignature;

    private Map<String, Long> actionCounts;

    private Double isoScore;
    @JsonProperty("isAnomaly")
    private Boolean isAnomaly;
    private String anomalyType;
    private Double typeConfidence;
    private Double anomalyProbability;
    private Double churnProbability;
    private Double ensembleRiskScore;
    private Integer personaCluster;
    private String binaryDetectorArtifact;

    private Boolean pathDeviation;
    private Double transitionProbability;
    private String transitionFromAction;
    private String transitionToAction;

    private List<String> top3NextActions;

    private Boolean ruleTriggered;
    private String ruleType;

    private Instant createdAt;
    private Instant updatedAt;
}
