package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ForecastConfig {
    private boolean enabled = true;
    private Map<String, ModelConfig> models = Map.of();
    private Map<String, Map<String, String>> fallbacks = Map.of();
    @JsonProperty("expected_alert_volume_formula")
    private String expectedAlertVolumeFormula;

    @JsonProperty("total_events")
    private TotalEvents totalEvents = new TotalEvents();
    @JsonProperty("anomaly_rate")
    private AnomalyRate anomalyRate = new AnomalyRate();

    public ModelConfig anomalyRateModel() {
        return models.getOrDefault("anomaly_rate", new ModelConfig());
    }

    public ModelConfig totalEventsModel() {
        return models.getOrDefault("total_events", new ModelConfig());
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelConfig {
        @JsonProperty("preferred_model")
        private String preferredModel;
        private String artifact;
        @JsonProperty("feature_order")
        private List<String> featureOrder = List.of();
        @JsonProperty("java_runtime")
        private String javaRuntime;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TotalEvents {
        @JsonProperty("recommended_for_springboot")
        private boolean recommendedForSpringboot;
        @JsonProperty("model_file")
        private String modelFile;
        @JsonProperty("model_type")
        private String modelType;
        @JsonProperty("feature_order")
        private List<String> featureOrder = List.of();
        @JsonProperty("missing_value")
        private double missingValue;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnomalyRate {
        @JsonProperty("recommended_for_springboot")
        private boolean recommendedForSpringboot;
        private String strategy;
        private String formula;
        @JsonProperty("required_feature")
        private String requiredFeature;
    }
}
