package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Result returned by the anomaly-type classifier.
 */
@Data
@Builder
public class AnomalyTypeResult {
    /* The predicted anomaly category label. */
    private String type;
    /* Model confidence score for the assigned type. */
    private double confidence;
}
