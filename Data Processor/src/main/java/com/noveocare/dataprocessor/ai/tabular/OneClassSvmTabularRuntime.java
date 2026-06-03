package com.noveocare.dataprocessor.ai.tabular;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OneClassSvmTabularRuntime implements TabularAnomalyModelRuntime {
    private final RuntimeArtifactService artifactService;
    private final AiTabularAnomalyProperties properties;
    private double gamma;
    private double intercept;
    private double[] dualCoef;
    private double[][] supportVectors;
    private final List<String> loadWarnings = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (!properties.isEnabled() || !properties.isOneclasssvmEnabled()) {
            loadWarnings.add("oneclasssvm_disabled");
            return;
        }
        if (!artifactService.modelExists(RuntimeArtifactService.TABULAR_ONECLASS_SVM)) {
            loadWarnings.add("oneclasssvm_artifact_missing");
            return;
        }
        try {
            JsonNode root = artifactService.readModelJson(RuntimeArtifactService.TABULAR_ONECLASS_SVM, JsonNode.class);
            gamma = root.path("gamma").asDouble();
            intercept = root.path("intercept").path(0).asDouble();
            dualCoef = doubleVector(root.path("dual_coef").path(0));
            supportVectors = doubleMatrix(root.path("support_vectors"));
            if (dualCoef.length != supportVectors.length) {
                loadWarnings.add("oneclasssvm_dimension_mismatch");
                dualCoef = null;
                supportVectors = null;
            }
            log.info("Loaded OneClassSVM JSON supportVectors={}", supportVectors == null ? 0 : supportVectors.length);
        } catch (Exception ex) {
            loadWarnings.add("oneclasssvm_runtime_unavailable");
            log.warn("OneClassSVM runtime unavailable", ex);
        }
    }

    @Override
    public TabularModelScore score(TabularAnomalyFeatureVector vector) {
        if (!isAvailable()) {
            return TabularModelScore.unavailable(modelName(), artifactName(), firstWarning("oneclasssvm_unavailable"));
        }
        long started = System.nanoTime();
        try {
            double decision = intercept;
            double[] z = vector.getScaledValues();
            for (int i = 0; i < supportVectors.length; i++) {
                decision += dualCoef[i] * Math.exp(-gamma * squaredDistance(z, supportVectors[i]));
            }
            double anomalyScore = -decision;
            double score100 = 100.0 * sigmoid(anomalyScore);
            return TabularModelScore.builder()
                    .modelName(modelName())
                    .artifactName(artifactName())
                    .available(true)
                    .rawScore(anomalyScore)
                    .score(anomalyScore)
                    .score100(score100)
                    .latencyMs((System.nanoTime() - started) / 1_000_000L)
                    .warnings(List.of())
                    .build();
        } catch (Exception ex) {
            return TabularModelScore.unavailable(modelName(), artifactName(), "oneclasssvm_score_failed");
        }
    }

    @Override
    public boolean isAvailable() {
        return dualCoef != null && supportVectors != null;
    }

    @Override
    public String modelName() {
        return "oneclasssvm";
    }

    @Override
    public String artifactName() {
        return "anomaly_oneclasssvm.json";
    }

    private double squaredDistance(double[] left, double[] right) {
        int limit = Math.min(left == null ? 0 : left.length, right == null ? 0 : right.length);
        double total = 0.0;
        for (int i = 0; i < limit; i++) {
            double diff = left[i] - right[i];
            total += diff * diff;
        }
        return total;
    }

    private double sigmoid(double value) {
        if (value >= 0.0) {
            double z = Math.exp(-value);
            return 1.0 / (1.0 + z);
        }
        double z = Math.exp(value);
        return z / (1.0 + z);
    }

    private double[] doubleVector(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new double[0];
        }
        double[] values = new double[node.size()];
        for (int i = 0; i < node.size(); i++) {
            values[i] = node.get(i).asDouble();
        }
        return values;
    }

    private double[][] doubleMatrix(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new double[0][0];
        }
        double[][] values = new double[node.size()][];
        for (int i = 0; i < node.size(); i++) {
            values[i] = doubleVector(node.get(i));
        }
        return values;
    }

    private String firstWarning(String fallback) {
        return loadWarnings.isEmpty() ? fallback : loadWarnings.get(0);
    }
}
