package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ModelScoresDto {
    private Double xgboostAnomalyScore;
    private Double xgboostAnomalyScore100;
    private Double lightgbmAlertScore;
    private Double lightgbmAlertScore100;
    private Double catboostAnomalyScore;
    private Double oneclasssvmNoveltyScore;
    private Double transformerSurpriseScore;
    private Double transformerSurpriseScoreRaw;
    private Double transformerRiskScore100;
    private Double tcnSurpriseScore;
    private Double tcnRiskScore100;
    private Double ruleRiskScore;
    private Double businessContextScore;
    private Double aggregationBoost;
    private Double finalRiskScore;
}
