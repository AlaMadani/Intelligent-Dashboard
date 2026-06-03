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
public class V36LlmExplanationResponse {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private String eventId;
    private Instant generatedAt;
    private String provider;
    private String model;
    private Boolean cached;
    private String evidenceHash;
    private String style;
    private String language;
    private String summary;
    private List<String> evidenceBullets;
    private String possibleInterpretation;
    private List<String> recommendedActions;
    private Map<String, Object> modelScoreExplanation;
    private String disclaimer;
    private Boolean fallback;
}
