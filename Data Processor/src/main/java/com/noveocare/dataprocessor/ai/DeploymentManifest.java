package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DeploymentManifest {
    private BinaryDetection binaryDetection = new BinaryDetection();
    private Classifier anomalyType = new Classifier();
    private Classifier churn = new Classifier();
    private Clustering clustering = new Clustering();
    private NextAction nextAction = new NextAction();
    private Map<String, ForecastArtifact> forecasting = new LinkedHashMap<>();
    private List<String> dashboardExports = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class BinaryDetection {
        private String preferred;
        private String fallback;
        private String featureColumns;
        private String numericMedians;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Classifier {
        private String model;
        private String featureColumns;
        private String numericMedians;
        private String labels;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Clustering {
        private String model;
        private List<String> clusterFeatures = new ArrayList<>();
        private String scalerParams;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class NextAction {
        private String artifact;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ForecastArtifact {
        private String forecastCsv;
        private Double mae;
        private Double rmse;
        private String prophetJson;
    }
}
