package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO representing the full evidence payload fed to the LLM for
 * explanation generation.
 * <p>
 * Bundles all evidence sources (model scores, contributions, sequence,
 * tabular, rules, anomaly attribution, churn, forecast) plus the
 * LLM instruction and raw payload so the LLM can produce a
 * comprehensive narrative.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36LlmEvidencePayloadDto {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** Unique event identifier this payload belongs to. */
    private String eventId;
    /** The insured person identifier. */
    private String insuredId;
    /** Session identifier grouping multiple events. */
    private String sessionId;
    /** Risk-related data (score, level, etc.) for the event. */
    private Map<String, Object> risk;
    /** Per-model raw scores (XGBoost, LightGBM, Transformer, TCN, rules …). */
    private V36ModelScoresDto modelScores;
    /** Contribution of each model/component to the final risk score. */
    private V36ModelContributionsDto modelContributions;
    /** Evidence from sequential/behavioral models. */
    private V36SequenceEvidenceDto sequenceEvidence;
    /** Evidence from tabular/feature-based models. */
    private V36TabularEvidenceDto tabularEvidence;
    /** Evidence from rule-based evaluation. */
    private V36RuleEvidenceDto ruleEvidence;
    /** Attribution details for the anomaly type classification. */
    private V36AnomalyTypeAttributionDto anomalyTypeAttribution;
    /** Churn-prediction context associated with the insured. */
    private V36ChurnContextDto churnContext;
    /** Forecast-prediction context (event volume, anomaly rate, etc.). */
    private V36ForecastContextDto forecastContext;
    /** Free-form metadata attached to the original event. */
    private Map<String, Object> eventMetadata;
    /** Instruction / prompt passed to the LLM for explanation generation. */
    private Map<String, Object> llmInstruction;
    /** Whether the LLM explanation was generated within the data-processor pipeline. */
    private Boolean llmExplanationInDataprocessor;
    /** The raw JSON payload as received from upstream, for debugging. */
    private Map<String, Object> rawPayload;
}
