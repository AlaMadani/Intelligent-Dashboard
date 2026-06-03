package com.noveocare.dataprocessor.ai.tabular;

public interface TabularAnomalyModelRuntime {
    TabularModelScore score(TabularAnomalyFeatureVector vector);
    boolean isAvailable();
    String modelName();
    String artifactName();
}
