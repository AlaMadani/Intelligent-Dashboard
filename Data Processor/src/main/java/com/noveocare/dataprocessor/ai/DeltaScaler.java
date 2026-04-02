package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Mirrors the scaler JSON file that stores mean and scale arrays.
 */
@Data
public class DeltaScaler {
    // The training export stores scalar statistics as one-element arrays.
    @JsonProperty("mean")
    private List<Double> mean;
    @JsonProperty("scale")
    private List<Double> scale;

    public double meanValue() {
        // Default to zero when the scaler file is missing its mean entry.
        return mean == null || mean.isEmpty() ? 0.0 : mean.get(0);
    }

    public double scaleValue() {
        // Default to one to avoid division by zero in downstream normalization.
        return scale == null || scale.isEmpty() ? 1.0 : scale.get(0);
    }
}
