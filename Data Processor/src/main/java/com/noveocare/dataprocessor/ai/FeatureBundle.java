package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FeatureBundle {
    private List<String> sessionNumericFeatures = new ArrayList<>();
    private List<String> sessionCategoricalFeatures = new ArrayList<>();
    private List<String> clusterNumericFeatures = new ArrayList<>();
    private Double isoThreshold;
    private boolean usesXgboostBinary;
    private boolean usesRandomForestType;
    private boolean usesProphet;
}
