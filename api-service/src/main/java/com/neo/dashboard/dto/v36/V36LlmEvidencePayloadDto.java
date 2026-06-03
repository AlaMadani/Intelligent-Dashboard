package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36LlmEvidencePayloadDto {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private String eventId;
    private String insuredId;
    private String sessionId;
    private Map<String, Object> risk;
    private V36ModelScoresDto modelScores;
    private V36SequenceEvidenceDto sequenceEvidence;
    private V36TabularEvidenceDto tabularEvidence;
    private V36RuleEvidenceDto ruleEvidence;
    private V36AnomalyTypeAttributionDto anomalyTypeAttribution;
    private V36ChurnContextDto churnContext;
    private V36ForecastContextDto forecastContext;
    private Map<String, Object> llmInstruction;
    private Boolean llmExplanationInDataprocessor;
    private JsonNode rawPayload;
}
