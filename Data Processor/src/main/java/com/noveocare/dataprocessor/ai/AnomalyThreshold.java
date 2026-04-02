package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Holds the calibrated reconstruction-error threshold and its quartile metadata.
 */
@Data
public class AnomalyThreshold {
    // Primary runtime threshold used to decide whether a score is anomalous.
    @JsonProperty("threshold")
    private double threshold;

    // Extra statistics retained for traceability and offline analysis.
    @JsonProperty("Q1")
    private double q1;
    @JsonProperty("Q3")
    private double q3;
    @JsonProperty("IQR")
    private double iqr;
}
