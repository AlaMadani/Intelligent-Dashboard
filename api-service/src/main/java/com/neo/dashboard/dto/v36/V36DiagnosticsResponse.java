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
public class V36DiagnosticsResponse {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private V36RuntimeHealthResponse runtimeHealth;
    private Map<String, Object> fieldCoverage;
    private Object modelLatency;
    private String fallbackMode;
    private List<String> warnings;
}
