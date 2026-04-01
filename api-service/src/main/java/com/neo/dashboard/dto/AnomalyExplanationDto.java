package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyExplanationDto {
    private Long anomalyEventId;
    private String source;
    private String model;
    private Instant generatedAt;
    private Boolean cached;
    private String explanation;
}
