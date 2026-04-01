package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/* DTO for next_action_predictions records. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NextActionPredictionDto {
    private Long id;
    private String insuredId;
    private String sessionId;
    private Instant predictedAt;
    private List<String> top3Actions;
}
