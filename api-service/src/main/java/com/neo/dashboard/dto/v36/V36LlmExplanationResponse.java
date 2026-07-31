package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Complete response DTO for an LLM-generated explanation of an alert.
 * <p>
 * Contains structured narrative sections (summary, risk, behavior, model,
 * rules, sequence), evidence bullets, recommended actions, score/rule
 * breakdowns, and metadata about the LLM provider, model, and caching status.
 * Fields are JSON-included only when non-null.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class V36LlmExplanationResponse {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** The event identifier this explanation corresponds to. */
    private String eventId;
    /** Instant when the explanation was generated. */
    private Instant generatedAt;
    /** LLM provider name (e.g. "openai", "anthropic", "azure"). */
    private String provider;
    /** Specific LLM model identifier used (e.g. "gpt-4", "claude-3"). */
    private String model;
    /** Whether this response was served from cache. */
    private Boolean cached;
    /** Source system or component that produced this explanation. */
    private String source;
    /** Whether the caller requested a cache bypass. */
    private Boolean forceRefresh;
    /** Hash of the input evidence used for cache-key computation. */
    private String evidenceHash;
    /** Narrative style used for generation (e.g. "technical", "simple"). */
    private String style;
    /** Language code used for the explanation (e.g. "en", "fr"). */
    private String language;
    /** High-level summary of the alert and its risk. */
    private String summary;
    /** Detailed narrative describing the risk assessment. */
    private String riskNarrative;
    /** Narrative describing the user's behavioral patterns. */
    private String behaviorNarrative;
    /** Narrative explaining model scores and their contributions. */
    private String modelNarrative;
    /** Narrative explaining which rules triggered and why. */
    private String rulesNarrative;
    /** Narrative explaining the sequence/behavioral analysis. */
    private String sequenceNarrative;
    /** Bullet-point list of key evidence items. */
    private List<String> evidenceBullets;
    /** Possible interpretation / hypothesis for the observed behavior. */
    private String possibleInterpretation;
    /** List of recommended actions for the investigator. */
    private List<String> recommendedActions;
    /** Detailed breakdown of each model's score contribution. */
    private Map<String, Object> modelScoreExplanation;
    /** Detailed explanation of each triggered rule. */
    private List<Map<String, Object>> triggeredRulesExplanation;
    /** Limitations or caveats of the LLM-generated explanation. */
    private List<String> limitations;
    /** Legal or compliance disclaimer text. */
    private String disclaimer;
    /** Whether the LLM fell back to a simpler generation strategy. */
    private Boolean fallback;
    /** Warning messages from the explanation generation process. */
    private List<String> warnings;
    /** Reason why the LLM finished generation (e.g. "stop", "length"). */
    private String finishReason;
    /** Raw response text from the LLM provider, for debugging. */
    private String rawProviderResponse;
    /** Unique identifier for the LLM request, for tracing. */
    private String llmRequestId;
}
