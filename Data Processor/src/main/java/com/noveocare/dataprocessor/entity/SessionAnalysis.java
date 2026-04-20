package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Persisted session-level summary containing derived statistics and inference results.
 */
@Entity
@Table(name = "session_analysis")
@Data
public class SessionAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Session identifiers and temporal boundaries.
    @Column(name = "insured_id", nullable = false)
    private String insuredId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "persona")
    private String persona;

    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "city")
    private String city;

    @Column(name = "month")
    private String month;

    @Column(name = "session_number")
    private Integer sessionNumber;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "first_action", columnDefinition = "NVARCHAR(512)")
    private String firstAction;

    @Column(name = "last_action", columnDefinition = "NVARCHAR(512)")
    private String lastAction;

    @Column(name = "first_route")
    private String firstRoute;

    @Column(name = "last_route")
    private String lastRoute;

    @Column(name = "session_length")
    private Integer totalEvents;

    @Column(name = "session_duration_seconds")
    private Long sessionDurationSeconds;

    // Aggregate metrics computed from the ordered session timeline.
    @Column(name = "unique_action_count")
    private Integer uniqueActions;

    @Column(name = "unique_routes")
    private Integer uniqueRoutes;

    @Column(name = "unique_ips_used")
    private Integer uniqueIpsUsed;

    @Column(name = "unique_devices_used")
    private Integer uniqueDevicesUsed;

    @Column(name = "total_kos")
    private Integer totalKOs;

    @Column(name = "total_oks")
    private Integer totalOKs;

    @Column(name = "longest_ko_streak")
    private Integer longestKoStreak;

    @Column(name = "ko_rate")
    private Double koRate;

    @Column(name = "mean_delta_seconds")
    private Double avgInterActionSeconds;

    @Column(name = "min_inter_action_seconds")
    private Double minInterActionSeconds;

    @Column(name = "max_inter_action_seconds")
    private Double maxInterActionSeconds;

    @Column(name = "action_diversity")
    private Double actionDiversity;

    @Column(name = "has_login")
    private Boolean hasLogin;

    @Column(name = "has_logout")
    private Boolean hasLogout;

    @Column(name = "ip_changed")
    private Boolean ipChanged;

    @Column(name = "device_changed")
    private Boolean deviceChanged;

    @Column(name = "total_download_actions")
    private Integer totalDownloadActions;

    @Column(name = "max_downloads_in_2_minutes")
    private Integer maxDownloadsIn2Minutes;

    @Column(name = "ping_pong_count")
    private Integer pingPongCount;

    @Column(name = "risk_score_max")
    private Double riskScoreMax;

    @Column(name = "risk_score_avg")
    private Double riskScoreAvg;

    @Column(name = "ended_abruptly")
    private Boolean endedAbruptly;

    @Column(name = "anomaly_event_count")
    private Integer anomalyEventCount;

    @Column(name = "anomaly_types_json", columnDefinition = "NVARCHAR(MAX)")
    private String anomalyTypesJson;

    @Column(name = "campaign_ids_json", columnDefinition = "NVARCHAR(MAX)")
    private String campaignIdsJson;

    @Column(name = "action_sequence_json", columnDefinition = "NVARCHAR(MAX)")
    private String actionSequenceJson;

    @Column(name = "route_sequence_json", columnDefinition = "NVARCHAR(MAX)")
    private String routeSequenceJson;

    @Column(name = "action_sequence_signature", columnDefinition = "NVARCHAR(MAX)")
    private String actionSequenceSignature;

    @Column(name = "route_sequence_signature", columnDefinition = "NVARCHAR(MAX)")
    private String routeSequenceSignature;

    @Column(name = "action_counts_json", columnDefinition = "NVARCHAR(MAX)")
    private String actionCountsJson;

    @Column(name = "iso_score")
    private Double isoScore;

    @Column(name = "is_anomaly")
    private Boolean isAnomaly;

    @Column(name = "anomaly_type")
    private String anomalyType;

    @Column(name = "type_confidence")
    private Double typeConfidence;

    @Column(name = "anomaly_probability")
    private Double anomalyProbability;

    @Column(name = "churn_probability")
    private Double churnProbability;

    @Column(name = "ensemble_risk_score")
    private Double ensembleRiskScore;

    @Column(name = "persona_cluster")
    private Integer personaCluster;

    @Column(name = "binary_detector_artifact")
    private String binaryDetectorArtifact;

    @Column(name = "feature_contributions_json", columnDefinition = "NVARCHAR(MAX)")
    private String featureContributionsJson;

    @Column(name = "explainability_text", columnDefinition = "NVARCHAR(MAX)")
    private String explainabilityText;

    @Column(name = "warnings_json", columnDefinition = "NVARCHAR(MAX)")
    private String warningsJson;

    @Column(name = "triggered_rules_json", columnDefinition = "NVARCHAR(MAX)")
    private String triggeredRulesJson;

    @Column(name = "context_tags_json", columnDefinition = "NVARCHAR(MAX)")
    private String contextTagsJson;

    @Column(name = "rare_transitions_json", columnDefinition = "NVARCHAR(MAX)")
    private String rareTransitionsJson;

    @Column(name = "path_deviation")
    private Boolean pathDeviation;

    @Column(name = "transition_probability")
    private Double transitionProbability;

    @Column(name = "transition_from_action", columnDefinition = "NVARCHAR(512)")
    private String transitionFromAction;

    @Column(name = "transition_to_action", columnDefinition = "NVARCHAR(512)")
    private String transitionToAction;

    @Column(name = "top3_next_actions", columnDefinition = "NVARCHAR(MAX)")
    private String top3NextActions;

    @Column(name = "rule_triggered")
    private Boolean ruleTriggered;

    @Column(name = "rule_type", columnDefinition = "NVARCHAR(MAX)")
    private String ruleType;

    // Persistence timestamp for this derived summary record.
    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
