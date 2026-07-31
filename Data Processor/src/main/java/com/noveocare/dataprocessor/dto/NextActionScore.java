package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Pair of a recommended next action and its associated probability.
 */
@Value
@Builder
public class NextActionScore {
    String action;
    double probability;
}
