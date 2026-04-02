package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Result returned by the anomaly-type classifier.
 */
@Data
@Builder
public class AnomalyTypeResult {
    private String type;
    private double confidence;
}
