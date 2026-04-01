package com.noveocare.dataprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyAlert {
    private String insuredId;
    private String sessionId;
    private String eventId;
    private String anomalyTier;
    private String anomalyType;
    private Double anomalyScore;
    private Double typeConfidence;
    private String ruleType;
    private Instant eventTime;
    private Instant detectedAt;
}
