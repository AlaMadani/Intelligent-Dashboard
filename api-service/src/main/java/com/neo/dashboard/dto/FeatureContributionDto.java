package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed view of one feature-importance record emitted by the worker.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeatureContributionDto {

    private String feature;
    private Double importance;
    private Object actualValue;
    private String description;
}
