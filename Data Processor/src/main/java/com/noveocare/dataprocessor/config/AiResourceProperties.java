package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiResourceProperties {
    private String basePath;
    private List<String> requiredResources = new ArrayList<>();
    private ModelPaths models = new ModelPaths();
    private FilePaths files = new FilePaths();

    @Data
    public static class ModelPaths {
        private String anomalyAutoencoder;
        private String anomalyTypeClassifier;
        private String nextActionGru;
        private String trendXgboost;
    }

    @Data
    public static class FilePaths {
        private String featureConfig;
        private String scalerDelta;
        private String anomalyThreshold;
        private String actionVocab;
        private String deviceVocab;
        private String countryVocab;
        private String typeVocab;
        private String subtypeVocab;
        private String anomalyTypeLabelMap;
        private String nextActionLabelMap;
        private String trendFeatureCols;
    }
}
