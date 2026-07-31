package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Aggregate churn-dashboard response for the V36 schema.
 * <p>
 * Contains high-level churn metrics (total users, risk breakdowns,
 * average probability) plus a list of top at-risk users and the
 * overall risk-distribution map.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ChurnDashboardResponse {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** Total number of users considered for churn analysis. */
    private long totalUsers;
    /** Count of users classified as high churn risk. */
    private long highChurnRiskUsers;
    /** Count of users classified as medium churn risk. */
    private long mediumChurnRiskUsers;
    /** Count of users classified as low churn risk. */
    private long lowChurnRiskUsers;
    /** Average churn probability across all users. */
    private double averageChurnProbability;
    /** Top at-risk users with their details (limited set for UI display). */
    private List<Map<String, Object>> topChurnRiskUsers;
    /** Map of risk-level labels to their corresponding user counts. */
    private Map<String, Long> churnRiskDistribution;
    /** Source system or component that produced this data. */
    private String source;
}
