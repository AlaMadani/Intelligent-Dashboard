package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

/**
 * Describes a single model's contribution to the fused risk score, including
 * its name, raw score, assigned weight, computed contribution, and availability.
 */
@Value
@Builder
public class ModelContribution {

    /* ---- Contribution fields ---- */
    String modelName;
    Double score100;
    double weight;
    double contribution;
    boolean available;
}
