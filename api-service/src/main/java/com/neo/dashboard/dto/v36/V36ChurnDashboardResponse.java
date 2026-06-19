package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ChurnDashboardResponse {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private long totalUsers;
    private long highChurnRiskUsers;
    private long mediumChurnRiskUsers;
    private long lowChurnRiskUsers;
    private double averageChurnProbability;
    private List<Map<String, Object>> topChurnRiskUsers;
    private Map<String, Long> churnRiskDistribution;
    private String source;
}
