package com.noveocare.dataprocessor.ai.tabular;

/**
 * Interface for tabular anomaly model runtimes. Implementations load a
 * specific model type and score feature vectors.
 */
public interface TabularAnomalyModelRuntime {
    TabularModelScore score(TabularAnomalyFeatureVector vector);
    boolean isAvailable();
    String modelName();
    String artifactName();
}
