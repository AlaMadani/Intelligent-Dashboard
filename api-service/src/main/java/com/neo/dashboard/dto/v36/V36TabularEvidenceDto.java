package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36TabularEvidenceDto {
    private String featureContract;
    private List<String> availableModels;
    private List<String> unavailableModels;
    private Map<String, Object> featureWarnings;
    private Map<String, Object> raw;
}
