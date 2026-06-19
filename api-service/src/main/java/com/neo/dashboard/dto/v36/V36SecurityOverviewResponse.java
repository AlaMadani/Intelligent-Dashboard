package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36SecurityOverviewResponse {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private Instant snapshotTimestamp;
    private long totalEventsToday;
    private long activeUsersToday;
    private double anomalyRateToday;
    private long criticalAlertsToday;
    private long highRiskAlertsToday;
    private double averageRiskScoreToday;
    private double predictedAnomalyRateTomorrow;
    private long predictedTotalEventsTomorrow;
    private long expectedAlertVolumeTomorrow;
    private Map<String, Long> topAnomalyTypes;
    private Map<String, Long> topTriggeredRules;
    private Map<String, Object> modelHealthSummary;
    private List<String> fieldCoverageWarnings;
    private String source;
}
