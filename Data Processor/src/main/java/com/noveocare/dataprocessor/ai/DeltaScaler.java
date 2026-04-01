package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class DeltaScaler {
    @JsonProperty("mean")
    private List<Double> mean;
    @JsonProperty("scale")
    private List<Double> scale;

    public double meanValue() {
        return mean == null || mean.isEmpty() ? 0.0 : mean.get(0);
    }

    public double scaleValue() {
        return scale == null || scale.isEmpty() ? 1.0 : scale.get(0);
    }
}
