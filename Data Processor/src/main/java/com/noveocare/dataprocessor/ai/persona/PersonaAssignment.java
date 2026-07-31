package com.noveocare.dataprocessor.ai.persona;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Immutable result of a persona assignment: cluster ID, label, confidence
 * score, source, and any warnings.
 */
@Value
@Builder
public class PersonaAssignment {
    int clusterId;
    String label;
    double confidence;
    String source;
    List<String> warnings;
}
