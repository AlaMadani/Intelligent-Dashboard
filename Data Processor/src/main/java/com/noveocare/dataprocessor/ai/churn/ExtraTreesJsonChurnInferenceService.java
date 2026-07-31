package com.noveocare.dataprocessor.ai.churn;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveocare.dataprocessor.ai.artifact.ChurnFeatureSchema;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiChurnProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionSummary;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Churn inference using a JSON-serialised ExtraTrees model. Parses tree
 * structures at startup and predicts churn probability per session.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExtraTreesJsonChurnInferenceService implements ChurnInferenceService {
    /* ---- Dependencies ---- */
    private final RuntimeArtifactService artifactService;
    private final AiChurnProperties properties;
    private final ChurnFeatureAssembler featureAssembler;
    private final List<String> loadWarnings = new ArrayList<>();
    private Model model;

    /* ========== Initialisation ========== */

    /* Loads the ExtraTrees model; falls back gracefully if unavailable. */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            loadWarnings.add("churn_disabled");
            return;
        }
        if (artifactService.getChurnFeatureSchema() == null || artifactService.getChurnFeatureSchema().getFeatureOrder().isEmpty()) {
            loadWarnings.add("churn_schema_unavailable");
            return;
        }
        if (!artifactService.modelExists(RuntimeArtifactService.CHURN_MODEL)) {
            loadWarnings.add("churn_model_unavailable");
            return;
        }
        try {
            model = parse(artifactService.readModelJson(RuntimeArtifactService.CHURN_MODEL, JsonNode.class));
            log.info("Loaded ExtraTrees churn JSON trees={}", model.trees().size());
        } catch (Exception ex) {
            loadWarnings.add("churn_extratrees_runtime_unavailable");
            log.warn("ExtraTrees churn runtime unavailable", ex);
        }
    }

    /* ========== ChurnInferenceService implementation ========== */

    @Override
    public ChurnPrediction predict(SessionSummary summary, List<AuditTrailEvent> events, boolean anomalousUser) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> features = featureAssembler.assemble(summary, events, anomalousUser);
        double[] vector = buildFeatureVector(features, warnings);
        if (!isAvailable()) {
            warnings.add(loadWarnings.isEmpty() ? "churn_model_unavailable" : loadWarnings.get(0));
            return ChurnPrediction.builder()
                    .available(false)
                    .probability(null)
                    .riskLevel("UNKNOWN")
                    .modelName(modelName())
                    .modelArtifact(artifactName())
                    .warnings(warnings)
                    .build();
        }
        double probability = model.predictProbability(vector);
        return ChurnPrediction.builder()
                .available(true)
                .probability(probability)
                .riskLevel(riskLevel(probability))
                .modelName(modelName())
                .modelArtifact(artifactName())
                .warnings(warnings)
                .build();
    }

    /* Builds the numerical feature vector according to the churn schema. */
    public double[] buildFeatureVector(Map<String, Object> features, List<String> warnings) {
        ChurnFeatureSchema schema = artifactService.getChurnFeatureSchema();
        double[] vector = new double[schema.getFeatureOrder().size()];
        for (int i = 0; i < schema.getFeatureOrder().size(); i++) {
            String feature = schema.getFeatureOrder().get(i);
            Map<String, Integer> mapping = schema.getCategoricalColumnsLabelEncodedAsNumeric().get(feature);
            Object raw = features == null ? null : features.get(feature);
            if (mapping != null) {
                String label = raw == null ? schema.getCategoricalDefault() : String.valueOf(raw);
                Integer encoded = mapping.get(label);
                if (encoded == null) {
                    encoded = mapping.getOrDefault(schema.getCategoricalDefault(), schema.getDefaultUnknownCategoryValue());
                    warnings.add("churn_unknown_category_" + feature);
                }
                vector[i] = encoded;
            } else {
                Double value = numeric(raw);
                if (value == null) {
                    value = schema.getNumericDefaults().getOrDefault(feature, schema.getMissingNumericValue());
                    warnings.add("churn_missing_numeric_" + feature);
                }
                vector[i] = value;
            }
        }
        return vector;
    }

    @Override
    public boolean isAvailable() {
        return model != null && !model.trees().isEmpty();
    }

    @Override
    public String modelName() {
        return "profile_only_ExtraTrees";
    }

    @Override
    public String artifactName() {
        return "churn_profile_only_ExtraTrees.json";
    }

    /* ========== Private helpers ========== */

    /* Parses the JSON tree array into an in-memory Model. */
    private Model parse(JsonNode root) {
        List<Tree> trees = new ArrayList<>();
        for (JsonNode treeNode : root.path("trees")) {
            trees.add(new Tree(
                    intArray(treeNode.path("children_left")),
                    intArray(treeNode.path("children_right")),
                    intArray(treeNode.path("feature_index")),
                    doubleArray(treeNode.path("threshold")),
                    doubleMatrix(treeNode.path("value"))));
        }
        return new Model(trees);
    }

    /* Maps a probability to HIGH / MEDIUM / LOW threshold. */
    private String riskLevel(double probability) {
        if (probability >= properties.getHighThreshold()) {
            return "HIGH";
        }
        if (probability >= properties.getMediumThreshold()) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /* Converts an Object to Double or returns null. */
    private Double numeric(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /* JSON array -> int[]. */
    private int[] intArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new int[0];
        }
        int[] values = new int[node.size()];
        for (int i = 0; i < node.size(); i++) {
            values[i] = node.get(i).asInt();
        }
        return values;
    }

    /* JSON array -> double[]. */
    private double[] doubleArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new double[0];
        }
        double[] values = new double[node.size()];
        for (int i = 0; i < node.size(); i++) {
            values[i] = node.get(i).asDouble();
        }
        return values;
    }

    /* JSON 2D array -> double[][]. */
    private double[][] doubleMatrix(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new double[0][0];
        }
        double[][] values = new double[node.size()][];
        for (int i = 0; i < node.size(); i++) {
            values[i] = doubleArray(node.get(i));
        }
        return values;
    }

    /* In-memory ExtraTrees ensemble averaging all tree predictions. */
    private record Model(List<Tree> trees) {
        double predictProbability(double[] vector) {
            if (trees.isEmpty()) {
                return 0.0;
            }
            double total = 0.0;
            for (Tree tree : trees) {
                total += tree.predictClassOneProbability(vector);
            }
            return Math.max(0.0, Math.min(1.0, total / trees.size()));
        }
    }

    /* Single decision tree in the ExtraTrees ensemble. */
    private record Tree(int[] left, int[] right, int[] featureIndex, double[] threshold, double[][] value) {
        double predictClassOneProbability(double[] vector) {
            int node = 0;
            int guard = 0;
            while (node >= 0 && node < left.length && guard++ < left.length + 4) {
                if (left[node] < 0 && right[node] < 0) {
                    return classOneProbability(node);
                }
                int feature = node < featureIndex.length ? featureIndex[node] : -1;
                double current = feature >= 0 && vector != null && feature < vector.length ? vector[feature] : 0.0;
                node = current <= threshold[node] ? left[node] : right[node];
            }
            return 0.0;
        }

        private double classOneProbability(int node) {
            if (node < 0 || node >= value.length || value[node].length == 0) {
                return 0.0;
            }
            double classZero = value[node].length > 0 ? value[node][0] : 0.0;
            double classOne = value[node].length > 1 ? value[node][1] : 0.0;
            double total = classZero + classOne;
            if (total <= 0.0) {
                return Math.max(0.0, Math.min(1.0, classOne));
            }
            return classOne / total;
        }
    }
}
