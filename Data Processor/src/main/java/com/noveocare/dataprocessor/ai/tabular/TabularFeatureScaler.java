package com.noveocare.dataprocessor.ai.tabular;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TabularFeatureScaler {
    @JsonProperty("artifact_type")
    private String artifactType;
    private String name;
    @JsonProperty("feature_order")
    private List<String> featureOrder = List.of();
    private List<Double> mean = List.of();
    private List<Double> scale = List.of();

    public double[] transform(double[] raw, List<String> warnings) {
        double[] scaled = new double[raw == null ? 0 : raw.length];
        List<String> localWarnings = warnings == null ? new ArrayList<>() : warnings;
        for (int i = 0; i < scaled.length; i++) {
            double value = raw[i];
            if (!Double.isFinite(value)) {
                value = 0.0;
                localWarnings.add("tabular_non_finite_raw_replaced");
            }
            double center = i < mean.size() ? mean.get(i) : 0.0;
            double divisor = i < scale.size() && scale.get(i) != null && scale.get(i) != 0.0 ? scale.get(i) : 1.0;
            double normalized = (value - center) / divisor;
            if (!Double.isFinite(normalized)) {
                normalized = 0.0;
                localWarnings.add("tabular_non_finite_scaled_replaced");
            }
            scaled[i] = normalized;
        }
        return scaled;
    }
}
