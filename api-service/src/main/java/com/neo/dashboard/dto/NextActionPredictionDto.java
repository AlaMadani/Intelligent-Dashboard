package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * DTO returned by the next-action endpoint, holding the model output plus the
 * metadata available for that prediction.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NextActionPredictionDto {
    /* Identity and correlation fields when the prediction comes from SQL. */
    private Long id;
    private String insuredId;
    private String sessionId;

    /* Prediction timestamp and ordered action suggestions. */
    private Instant predictedAt;
    private List<String> top3Actions;
}
