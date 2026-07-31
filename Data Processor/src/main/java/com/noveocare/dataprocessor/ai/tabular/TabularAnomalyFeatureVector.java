package com.noveocare.dataprocessor.ai.tabular;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Feature vector for tabular anomaly models: raw and scaled values, feature
 * order, and warnings.
 */
@Value
@Builder(toBuilder = true)
public class TabularAnomalyFeatureVector {
    String contractArtifact;
    List<String> featureOrder;
    double[] rawValues;
    double[] scaledValues;
    Map<String, Double> rawFeatureMap;
    List<String> warnings;

    /* Returns the number of scaled features. */
    public int length() {
        return scaledValues == null ? 0 : scaledValues.length;
    }
}
