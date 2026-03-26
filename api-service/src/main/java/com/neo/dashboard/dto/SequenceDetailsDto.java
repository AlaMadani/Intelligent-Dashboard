package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/* DTO aggregating sequence details and prediction outputs. */
@Data
@AllArgsConstructor
public class SequenceDetailsDto {
    private Long alertId;
    private Integer userKey;
    private String sequenceJson;
    private String predictionJson;
    private String predictionSummary;
    private Instant createdAt;
}
