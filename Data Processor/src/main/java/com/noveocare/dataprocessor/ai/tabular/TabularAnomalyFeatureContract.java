package com.noveocare.dataprocessor.ai.tabular;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Describes the tabular anomaly feature contract: feature order, count, and
 * logical groups.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TabularAnomalyFeatureContract {
    @JsonProperty("artifact_type")
    private String artifactType;
    @JsonProperty("feature_order")
    private List<String> featureOrder = List.of();
    @JsonProperty("n_features")
    private int featureCount;
    private String source;
    private Map<String, List<String>> groups = Map.of();
}
