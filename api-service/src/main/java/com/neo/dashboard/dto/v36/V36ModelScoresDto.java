package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO holding the raw output scores from every model in the ensemble.
 * <p>
 * Includes scores from XGBoost, LightGBM, CatBoost, OneClassSVM,
 * Transformer, TCN, rule-based evaluation, and business-context
 * scoring, as well as the aggregation boost and final fused score.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ModelScoresDto {
    /** Raw XGBoost anomaly score (0.0 – 1.0). */
    private Double xgboostAnomalyScore;
    /** XGBoost anomaly score scaled to 0–100 range. */
    private Double xgboostAnomalyScore100;
    /** Raw LightGBM alert score (0.0 – 1.0). */
    private Double lightgbmAlertScore;
    /** LightGBM alert score scaled to 0–100 range. */
    private Double lightgbmAlertScore100;
    /** Raw CatBoost anomaly score (0.0 – 1.0). */
    private Double catboostAnomalyScore;
    /** Novelty score from the OneClassSVM model. */
    private Double oneclasssvmNoveltyScore;
    /** Raw surprise score from the Transformer model. */
    private Double transformerSurpriseScore;
    /** Unnormalized raw surprise score from the Transformer model. */
    private Double transformerSurpriseScoreRaw;
    /** Transformer risk score scaled to 0–100 range. */
    private Double transformerRiskScore100;
    /** Raw surprise score from the TCN (Temporal Convolutional Network) model. */
    private Double tcnSurpriseScore;
    /** TCN risk score scaled to 0–100 range. */
    private Double tcnRiskScore100;
    /** Risk score contribution from rule-based evaluation. */
    private Double ruleRiskScore;
    /** Score contribution from business-context evaluation. */
    private Double businessContextScore;
    /** Boost factor applied during score aggregation. */
    private Double aggregationBoost;
    /** Final composite risk score after fusing all model outputs. */
    private Double finalRiskScore;
}
