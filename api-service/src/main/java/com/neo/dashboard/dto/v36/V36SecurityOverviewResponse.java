package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * High-level security overview response for the V36 dashboard.
 * <p>
 * Provides today's statistics (total events, active users, anomaly rate,
 * alert counts, average risk score), tomorrow's forecast predictions,
 * top anomaly types and rules, model health summary, and field-coverage
 * warnings.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36SecurityOverviewResponse {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** Instant when this snapshot was captured. */
    private Instant snapshotTimestamp;
    /** Total number of events processed today. */
    private long totalEventsToday;
    /** Number of unique active users today. */
    private long activeUsersToday;
    /** Ratio of anomalous events to total events today. */
    private double anomalyRateToday;
    /** Number of critical (highest severity) alerts today. */
    private long criticalAlertsToday;
    /** Number of high-risk alerts today. */
    private long highRiskAlertsToday;
    /** Average risk score across all events today. */
    private double averageRiskScoreToday;
    /** Predicted anomaly rate for tomorrow. */
    private double predictedAnomalyRateTomorrow;
    /** Predicted total number of events for tomorrow. */
    private long predictedTotalEventsTomorrow;
    /** Expected alert volume for tomorrow. */
    private long expectedAlertVolumeTomorrow;
    /** Map of anomaly types to their occurrence counts. */
    private Map<String, Long> topAnomalyTypes;
    /** Map of rule codes to their trigger counts. */
    private Map<String, Long> topTriggeredRules;
    /** Summary of per-model health states. */
    private Map<String, Object> modelHealthSummary;
    /** Field-coverage warnings for missing or sparse features. */
    private List<String> fieldCoverageWarnings;
    /** Source system or component that produced this overview. */
    private String source;
}
