package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PredictionDto {
    private String predictionJson;
    private String predictionSummary;
}
