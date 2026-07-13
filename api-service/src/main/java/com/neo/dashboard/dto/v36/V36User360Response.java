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
public class V36User360Response {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private String insuredId;
    private V36PersonaDisabledDto persona;
    private Map<String, Object> churn;
    private Map<String, Object> risk;
    private Map<String, Object> baseline;
    private List<Map<String, Object>> recentSessions;
    private List<Map<String, Object>> riskTimeline;
    private Map<String, Object> nextEventPrediction;
}
