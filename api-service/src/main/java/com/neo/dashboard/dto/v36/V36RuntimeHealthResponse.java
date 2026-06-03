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
public class V36RuntimeHealthResponse {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private String runtimeVersion;
    private String artifactBasePath;
    private Boolean personaEnabled;
    private Boolean llmExplanationInDataprocessor;
    private Boolean llmEvidencePayloadEnabled;
    private String fallbackMode;
    private String status;
    private String message;
    private Map<String, V36ModelRuntimeStateDto> modelHealth;
    private List<String> warnings;

    public static V36RuntimeHealthResponse unknown() {
        V36RuntimeHealthResponse response = new V36RuntimeHealthResponse();
        response.setRuntimeVersion(CacheKeys.V36_SCHEMA_VERSION);
        response.setStatus("UNKNOWN");
        response.setMessage("Runtime health snapshot not available");
        response.setPersonaEnabled(false);
        response.setLlmExplanationInDataprocessor(false);
        response.setLlmEvidencePayloadEnabled(false);
        response.setModelHealth(Map.of());
        response.setWarnings(List.of("Redis key " + CacheKeys.AI_RUNTIME_HEALTH_V36 + " is missing"));
        return response;
    }
}
