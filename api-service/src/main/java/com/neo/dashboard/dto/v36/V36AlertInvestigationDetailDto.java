package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detailed DTO for a single alert investigation view in the V36 dashboard.
 * <p>
 * Aggregates all evidence types (model scores, sequence, tabular, rules,
 * anomaly-type attribution, churn/forecast context, persona, LLM payload
 * metadata) plus session lifecycle information so the UI can render a
 * complete investigation panel without additional lookups.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36AlertInvestigationDetailDto {
    /** Schema version for backward-compatible response parsing. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** Primary alert identifier. */
    private Long id;
    /** Foreign key to the anomaly record in the persistence database. */
    private Long anomalyDbId;
    /** Unique event identifier (could be a UUID or business event ID). */
    private String eventId;
    /** Record / transaction identifier within the source system. */
    private String recordId;
    /** The insured person identifier this alert relates to. */
    private String insuredId;
    /** Session identifier grouping multiple events for the same user. */
    private String sessionId;
    /** Timestamp when the original event occurred. */
    private Instant timestamp;
    /** Qualitative risk level label (e.g. LOW, MEDIUM, HIGH, CRITICAL). */
    private String riskLevel;
    /** Final composite risk score after fusing all model outputs. */
    private Double finalRiskScore;
    /** Type/category of anomaly detected (e.g. "behavioral", "velocity"). */
    private String anomalyType;
    /** Confidence level of the anomaly-type classification. */
    @JsonAlias("anomalyTypeConfidence")
    private Double anomalyTypeConfidence;
    /** List of rule codes that triggered during evaluation. */
    private List<String> triggeredRules;
    /** Free-form metadata attached to the original event. */
    private Map<String, Object> eventMetadata;
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
    /** Persona/cluster information for the user. */
    private V36PersonaDisabledDto persona;
    /** Runtime warnings emitted by model or pipeline execution. */
    private List<String> runtimeWarnings;
    /** Whether an LLM-generated evidence payload is available. */
    private Boolean llmEvidencePayloadAvailable;
    /** Redis key under which the LLM evidence payload is stored. */
    @JsonAlias({"llmEvidencePayloadRedisKey", "llmEvidenceRedisKey"})
    private String llmEvidenceRedisKey;
    /** Free-form LLM-generated data (explanations, narratives, etc.). */
    private Map<String, Object> llm;
    /** Evidence related to next-event prediction. */
    private Map<String, Object> nextEventPredictionEvidence;
    /** Source system or component that produced this alert. */
    private String source;
    /** Human-readable warning messages for the investigation UI. */
    private List<String> warnings;
    /** The raw JSON payload as received from upstream, for debugging. */
    private Map<String, Object> rawPayload;
    /** Reason why the session ended (if applicable). */
    private String sessionEndReason;
    /** Whether the session was explicitly ended by the user/system. */
    private Boolean sessionEndedExplicitly;
    /** Instant when the session ended. */
    private Instant sessionEndedAt;
    /** Total duration of the session in milliseconds. */
    private Long sessionDurationMs;
    /** Number of events recorded in this session. */
    private Integer sessionEventCount;
    /** Aggregated session-lifecycle map built by {@link #buildSessionLifecycle()}. */
    private Map<String, Object> sessionLifecycle;

    /**
     * Appends a warning message to the {@link #warnings} list, creating
     * the list or converting it to a mutable {@link ArrayList} if it was
     * originally immutable (e.g. {@link List#of}).
     *
     * @param warning the warning text to add
     */
    public void addWarning(String warning) {
        /* Ensure the warnings list is mutable before adding */
        if (warnings == null) {
            /* Create a new mutable list when none exists */
            warnings = new ArrayList<>();
        } else if (!(warnings instanceof java.util.ArrayList)) {
            /* Wrap an immutable list into a mutable ArrayList */
            warnings = new ArrayList<>(warnings);
        }
        warnings.add(warning);
    }

    /**
     * Builds the {@link #sessionLifecycle} map from individual session
     * fields ({@link #sessionEndReason}, {@link #sessionEndedExplicitly},
     * {@link #sessionEndedAt}, {@link #sessionDurationMs},
     * {@link #sessionEventCount}) so the API returns a single structured
     * object. If none of the fields are populated the map is set to null.
     */
    public void buildSessionLifecycle() {
        /* Collect all non-null session fields into an ordered map */
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        if (sessionEndReason != null) lifecycle.put("sessionEndReason", sessionEndReason);
        if (sessionEndedExplicitly != null) lifecycle.put("sessionEndedExplicitly", sessionEndedExplicitly);
        if (sessionEndedAt != null) lifecycle.put("sessionEndedAt", sessionEndedAt.toString());
        if (sessionDurationMs != null) lifecycle.put("sessionDurationMs", sessionDurationMs);
        if (sessionEventCount != null) lifecycle.put("sessionEventCount", sessionEventCount);
        /* Only set the lifecycle map if at least one field was populated */
        this.sessionLifecycle = lifecycle.isEmpty() ? null : lifecycle;
    }
}
