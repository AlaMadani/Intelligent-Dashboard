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
public class ClusterScalerParams {
    private List<String> featureOrder = new ArrayList<>();
    private List<Double> mean = new ArrayList<>();
    private List<Double> scale = new ArrayList<>();
}
