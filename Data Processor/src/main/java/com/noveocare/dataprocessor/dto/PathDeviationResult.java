package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Result of comparing an observed transition against expected path probabilities.
 */
@Value
@Builder
public class PathDeviationResult {
    boolean deviated;
    String fromAction;
    String toAction;
    Double transitionProbability;
}
