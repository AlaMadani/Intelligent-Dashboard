package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * Configuration POJO for sequence anomaly score formula weights, loaded from
 * anomaly_score_config.json.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnomalyScoreConfig {
    @JsonProperty("cat_score_weights_by_column")
    private Map<String, Double> catScoreWeightsByColumn = Map.of();
    @JsonProperty("cat_score_norm_by_column")
    private Map<String, Double> catScoreNormByColumn = Map.of();
    @JsonProperty("cont_score_w")
    private double contScoreW = 1.0;
    @JsonProperty("ctx_score_w")
    private double ctxScoreW = 0.3;
    @JsonProperty("score_formula")
    private String scoreFormula;
}
