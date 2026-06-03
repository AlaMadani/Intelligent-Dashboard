package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChurnFeatureSchema {
    @JsonProperty("model_file")
    private String modelFile;
    @JsonProperty("model_type")
    private String modelType;
    @JsonProperty("feature_order")
    private List<String> featureOrder = List.of();
    @JsonProperty("feature_count")
    private int featureCount;
    @JsonAlias("categorical_mappings")
    @JsonProperty("categorical_columns_label_encoded_as_numeric")
    private Map<String, Map<String, Integer>> categoricalColumnsLabelEncodedAsNumeric = Map.of();
    @JsonProperty("numeric_defaults")
    private Map<String, Double> numericDefaults = Map.of();
    @JsonProperty("categorical_default")
    private String categoricalDefault = "UNK";
    @JsonAlias("categorical_default_value")
    @JsonProperty("default_unknown_category_value")
    private int defaultUnknownCategoryValue;
    @JsonProperty("missing_numeric_value")
    private double missingNumericValue;
    @JsonProperty("risk_buckets")
    private Map<String, List<Double>> riskBuckets = Map.of();
}
