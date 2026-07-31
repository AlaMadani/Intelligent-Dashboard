package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO holding evidence produced by tabular/feature-based models.
 * <p>
 * Includes the feature contract version, lists of available and
 * unavailable models, feature-level warnings, and the raw tabular
 * model output.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36TabularEvidenceDto {
    /** Feature contract version or identifier used for this evaluation. */
    private String featureContract;
    /** List of models that were available and produced scores. */
    private List<String> availableModels;
    /** List of models that were unavailable and did not produce scores. */
    private List<String> unavailableModels;
    /** Feature-level warnings (e.g. missing values, out-of-range). */
    private Map<String, Object> featureWarnings;
    /** Raw/unprocessed tabular model output data. */
    private Map<String, Object> raw;
}
