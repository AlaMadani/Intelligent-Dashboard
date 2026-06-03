package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ModelContributionsDto {
    private Double xgboost;
    private Double lightgbm;
    private Double transformer;
    private Double tcn;
    private Double rules;
    private Double businessContext;
    private Double aggregationBoost;
    private Map<String, Object> raw;
}
