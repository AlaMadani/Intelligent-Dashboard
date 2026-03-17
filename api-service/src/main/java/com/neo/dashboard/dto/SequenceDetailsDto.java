package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

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
