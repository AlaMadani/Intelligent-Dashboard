package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
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
public class V36AlertInvestigationDetailDto {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private Long id;
    private Long anomalyDbId;
    private String eventId;
    private String recordId;
    private String insuredId;
    private String sessionId;
    private Instant timestamp;
    private String riskLevel;
    private Double finalRiskScore;
    private String anomalyType;
    @JsonAlias("anomalyTypeConfidence")
    private Double anomalyTypeConfidence;
    private List<String> triggeredRules;
    private Map<String, Object> eventMetadata;
    private V36ModelScoresDto modelScores;
    private V36ModelContributionsDto modelContributions;
    private V36SequenceEvidenceDto sequenceEvidence;
    private V36TabularEvidenceDto tabularEvidence;
    private V36RuleEvidenceDto ruleEvidence;
    private V36AnomalyTypeAttributionDto anomalyTypeAttribution;
    private V36ChurnContextDto churnContext;
    private V36ForecastContextDto forecastContext;
    private V36PersonaDisabledDto persona;
    private List<String> runtimeWarnings;
    private Boolean llmEvidencePayloadAvailable;
    @JsonAlias({"llmEvidencePayloadRedisKey", "llmEvidenceRedisKey"})
    private String llmEvidenceRedisKey;
    private Map<String, Object> llm;
    private String source;
    private List<String> warnings;
    private JsonNode rawPayload;
}
