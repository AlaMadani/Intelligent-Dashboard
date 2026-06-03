package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ChurnContextDto {
    @JsonAlias("churnProbability")
    private Double probability;
    @JsonAlias("churnRiskLevel")
    private String riskLevel;
    private String modelName;
    private String modelArtifact;
    private Map<String, Object> featureWarnings;
}
