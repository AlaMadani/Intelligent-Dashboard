package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PersonaRuntimeKmeansConfig {
    private String source;
    private String algorithm;
    private int k;
    @JsonProperty("scaler_mean")
    private List<Double> scalerMean = List.of();
    @JsonProperty("scaler_scale")
    private List<Double> scalerScale = List.of();
    @JsonProperty("pca_mean")
    private List<Double> pcaMean = List.of();
    @JsonProperty("pca_components")
    private List<List<Double>> pcaComponents = List.of();
    @JsonProperty("kmeans_centroids")
    private List<List<Double>> kmeansCentroids = List.of();
    @JsonProperty("cluster_metadata")
    private Map<String, ClusterMetadata> clusterMetadata = Map.of();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClusterMetadata {
        private String label;
        @JsonProperty("dominant_persona")
        private String dominantPersona;
        private Integer size;
        @JsonProperty("persona_distribution")
        private Map<String, Double> personaDistribution = Map.of();
        @JsonProperty("persona_counts")
        private Map<String, Integer> personaCounts = Map.of();
    }
}
