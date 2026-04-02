package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds every configurable AI resource path from application.yaml.
 */
@Data
@ConfigurationProperties(prefix = "app.ai")
public class AiResourceProperties {
    // Base resource location used by all model and metadata loaders.
    private String basePath;
    private List<String> requiredResources = new ArrayList<>();
    private ModelPaths models = new ModelPaths();
    private FilePaths files = new FilePaths();

    @Data
    public static class ModelPaths {
        // Binary or serialized assets used directly for runtime inference.
        private String anomalyAutoencoder;
        private String anomalyTypeClassifier;
        private String nextActionGru;
        private String trendXgboost;
    }

    @Data
    public static class FilePaths {
        // JSON metadata and vocab files consumed by loaders and validators.
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
