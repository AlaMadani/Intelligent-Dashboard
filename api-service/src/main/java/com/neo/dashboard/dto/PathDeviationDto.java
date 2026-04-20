package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes one unusual transition detected in the user journey.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PathDeviationDto {

    private Boolean deviated;
    private String fromAction;
    private String toAction;
    private Double transitionProbability;
}
