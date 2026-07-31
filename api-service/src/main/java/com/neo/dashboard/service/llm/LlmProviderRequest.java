package com.neo.dashboard.service.llm;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Immutable data class that carries all input parameters needed by an LLM provider
 * to generate a natural-language explanation or prediction. Built via Lombok's builder pattern.
 */
@Data
@Builder
public class LlmProviderRequest {
    /** Unique identifier for tracing this specific LLM request across logs. */
    private String llmRequestId;
    /** System-level instruction that sets the LLM's role, tone, and constraints. */
    private String systemPrompt;
    /** The user's actual query or context data to be explained or analysed. */
    private String userPrompt;
    /** The anomaly event ID that prompted this LLM request — used for correlation. */
    private String eventId;
    /** Hash of the evidence data so the cache can detect stale explanations. */
    private String evidenceHash;
    /** Requested explanation style (e.g. "executive", "technical", "simple"). */
    private String style;
    /** Target language for the explanation (e.g. "en", "fr", "ar"). */
    private String language;
    /** Whether the generated explanation should include recommended action items. */
    private boolean includeRecommendedActions;
    /** Extensible bag of additional metadata that providers may use for context. */
    private Map<String, Object> metadata;
}
