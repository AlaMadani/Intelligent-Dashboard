package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO holding the contribution weight of each model/component toward
 * the final composite risk score.
 * <p>
 * Each field represents the relative influence (0.0 – 1.0) of that
 * component. The {@link #raw} map carries any additional model-specific
 * contribution data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ModelContributionsDto {
    /** Contribution weight from the XGBoost model. */
    private Double xgboost;
    /** Contribution weight from the LightGBM model. */
    private Double lightgbm;
    /** Contribution weight from the Transformer model. */
    private Double transformer;
    /** Contribution weight from the TCN (Temporal Convolutional Network) model. */
    private Double tcn;
    /** Contribution weight from rule-based evaluation. */
    private Double rules;
    /** Contribution weight from business-context scoring. */
    private Double businessContext;
    /** Boost factor applied during aggregation of model outputs. */
    private Double aggregationBoost;
    /** Raw/unprocessed contribution data from the fusion pipeline. */
    private Map<String, Object> raw;
}
