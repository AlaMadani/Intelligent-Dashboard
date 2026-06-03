package com.noveocare.dataprocessor.ai.tree;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public class XGBoostJsonPredictor {
    private final String objective;
    private final double baseMargin;
    private final List<Tree> trees;

    public XGBoostJsonPredictor(JsonNode root) {
        JsonNode learner = root.path("learner");
        this.objective = learner.path("objective").path("name").asText("");
        double baseScore = parseBaseScore(learner.path("learner_model_param").path("base_score").asText("0"));
        this.baseMargin = "binary:logistic".equals(objective) && baseScore > 0.0 && baseScore < 1.0
                ? Math.log(baseScore / (1.0 - baseScore))
                : baseScore;
        this.trees = parseTrees(learner.path("gradient_booster").path("model").path("trees"));
    }

    public double predict(double[] features) {
        double raw = predictRaw(features);
        if ("binary:logistic".equals(objective)) {
            return sigmoid(raw);
        }
        return raw;
    }

    public double predictRaw(double[] features) {
        double score = baseMargin;
        for (Tree tree : trees) {
            score += tree.predict(features);
        }
        return score;
    }

    public String objective() {
        return objective;
    }

    public int treeCount() {
        return trees.size();
    }

    private List<Tree> parseTrees(JsonNode treeNodes) {
        List<Tree> parsed = new ArrayList<>();
        if (treeNodes == null || !treeNodes.isArray()) {
            return parsed;
        }
        for (JsonNode node : treeNodes) {
            parsed.add(new Tree(
                    intArray(node.path("left_children")),
                    intArray(node.path("right_children")),
                    intArray(node.path("split_indices")),
                    doubleArray(node.path("split_conditions")),
                    doubleArray(node.path("base_weights")),
                    booleanArray(node.path("default_left"))));
        }
        return parsed;
    }

    private static double parseBaseScore(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        String normalized = value.replace("[", "").replace("]", "").trim();
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private static int[] intArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new int[0];
        }
        int[] values = new int[node.size()];
        for (int i = 0; i < node.size(); i++) {
            values[i] = node.get(i).asInt();
        }
        return values;
    }

    private static double[] doubleArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new double[0];
        }
        double[] values = new double[node.size()];
        for (int i = 0; i < node.size(); i++) {
            values[i] = node.get(i).asDouble();
        }
        return values;
    }

    private static boolean[] booleanArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new boolean[0];
        }
        boolean[] values = new boolean[node.size()];
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            values[i] = item.isBoolean() ? item.asBoolean() : item.asInt() != 0;
        }
        return values;
    }

    private static double sigmoid(double value) {
        if (value >= 0) {
            double z = Math.exp(-value);
            return 1.0 / (1.0 + z);
        }
        double z = Math.exp(value);
        return z / (1.0 + z);
    }

    private record Tree(int[] leftChildren,
                        int[] rightChildren,
                        int[] splitIndices,
                        double[] splitConditions,
                        double[] baseWeights,
                        boolean[] defaultLeft) {
        double predict(double[] features) {
            int node = 0;
            int guard = 0;
            while (node >= 0 && node < baseWeights.length && guard++ < baseWeights.length + 4) {
                int left = node < leftChildren.length ? leftChildren[node] : -1;
                int right = node < rightChildren.length ? rightChildren[node] : -1;
                if (left < 0 && right < 0) {
                    return baseWeights[node];
                }
                int featureIndex = node < splitIndices.length ? splitIndices[node] : -1;
                double threshold = node < splitConditions.length ? splitConditions[node] : 0.0;
                double featureValue = featureIndex >= 0 && features != null && featureIndex < features.length
                        ? features[featureIndex]
                        : Double.NaN;
                boolean missing = !Double.isFinite(featureValue);
                boolean goLeft = missing ? node < defaultLeft.length && defaultLeft[node] : featureValue < threshold;
                node = goLeft ? left : right;
            }
            return 0.0;
        }
    }
}
