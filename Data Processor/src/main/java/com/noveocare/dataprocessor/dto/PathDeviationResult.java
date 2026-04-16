package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PathDeviationResult {
    boolean deviated;
    String fromAction;
    String toAction;
    Double transitionProbability;
}
