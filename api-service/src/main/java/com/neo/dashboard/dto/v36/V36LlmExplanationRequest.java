package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for triggering or customizing an LLM-generated explanation.
 * <p>
 * Allows the caller to bypass caching ({@link #forceRefresh}), choose
 * a narrative {@link #style} (e.g. "technical", "simple"), set the
 * output {@link #language}, and optionally include recommended actions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36LlmExplanationRequest {
    /** If true, bypass the cached explanation and regenerate from the LLM. */
    private Boolean forceRefresh;
    /** Desired narrative style (e.g. "technical", "simple", "executive"). */
    private String style;
    /** Desired output language (e.g. "en", "fr", "es"). */
    private String language;
    /** Whether to include recommended actions in the explanation. */
    private Boolean includeRecommendedActions;
}
