package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AnomalyThreshold {
    @JsonProperty("threshold")
    private double threshold;
    @JsonProperty("Q1")
    private double q1;
    @JsonProperty("Q3")
    private double q3;
    @JsonProperty("IQR")
    private double iqr;
}
