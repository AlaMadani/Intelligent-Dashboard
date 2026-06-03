package com.noveocare.dataprocessor.ai.tree;

import java.util.ArrayList;
import java.util.List;

public class LightGbmTxtPredictor {
    private final List<Tree> trees;

    public LightGbmTxtPredictor(String modelText) {
        this.trees = parseTrees(modelText);
    }

    public double predictProbability(double[] features) {
        return sigmoid(predictRaw(features));
    }

    public double predictRaw(double[] features) {
        double score = 0.0;
        for (Tree tree : trees) {
            score += tree.predict(features);
        }
        return score;
    }

    public int treeCount() {
        return trees.size();
    }

    private List<Tree> parseTrees(String modelText) {
        List<Tree> parsed = new ArrayList<>();
        if (modelText == null || modelText.isBlank()) {
            return parsed;
        }
        String[] lines = modelText.split("\\R");
        TreeBuilder builder = null;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("Tree=")) {
                if (builder != null && builder.complete()) {
                    parsed.add(builder.build());
                }
                builder = new TreeBuilder();
                continue;
            }
            if (builder == null || !line.contains("=")) {
                continue;
            }
            String key = line.substring(0, line.indexOf('='));
            String value = line.substring(line.indexOf('=') + 1);
            switch (key) {
                case "split_feature" -> builder.splitFeature = intList(value);
                case "threshold" -> builder.threshold = doubleList(value);
                case "left_child" -> builder.leftChild = intList(value);
                case "right_child" -> builder.rightChild = intList(value);
                case "leaf_value" -> builder.leafValue = doubleList(value);
                default -> {
                }
            }
        }
        if (builder != null && builder.complete()) {
            parsed.add(builder.build());
        }
        return parsed;
    }

    private static int[] intList(String value) {
        String[] parts = value.trim().split("\\s+");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i]);
        }
        return result;
    }

    private static double[] doubleList(String value) {
        String[] parts = value.trim().split("\\s+");
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Double.parseDouble(parts[i]);
        }
        return result;
    }

    private static double sigmoid(double value) {
        if (value >= 0) {
            double z = Math.exp(-value);
            return 1.0 / (1.0 + z);
        }
        double z = Math.exp(value);
        return z / (1.0 + z);
    }

    private static class TreeBuilder {
        int[] splitFeature;
        double[] threshold;
        int[] leftChild;
        int[] rightChild;
        double[] leafValue;

        boolean complete() {
            return splitFeature != null && threshold != null && leftChild != null && rightChild != null && leafValue != null;
        }

        Tree build() {
            return new Tree(splitFeature, threshold, leftChild, rightChild, leafValue);
        }
    }

    private record Tree(int[] splitFeature,
                        double[] threshold,
                        int[] leftChild,
                        int[] rightChild,
                        double[] leafValue) {
        double predict(double[] features) {
            int node = 0;
            int guard = 0;
            while (node >= 0 && node < splitFeature.length && guard++ < splitFeature.length + 4) {
                int featureIndex = splitFeature[node];
                double featureValue = featureIndex >= 0 && features != null && featureIndex < features.length
                        ? features[featureIndex]
                        : Double.NaN;
                boolean goLeft = !Double.isFinite(featureValue) || featureValue <= threshold[node];
                int child = goLeft ? leftChild[node] : rightChild[node];
                if (child < 0) {
                    int leafIndex = -child - 1;
                    return leafIndex >= 0 && leafIndex < leafValue.length ? leafValue[leafIndex] : 0.0;
                }
                node = child;
            }
            return 0.0;
        }
    }
}
