package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/* DTO for model prediction payloads returned to the client. */
@Data
@AllArgsConstructor
public class PredictionDto {
    private String predictionJson;
    private String predictionSummary;
}
