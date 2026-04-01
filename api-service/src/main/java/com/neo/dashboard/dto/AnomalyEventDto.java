package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/* DTO for anomaly_events records. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyEventDto {
    private Long id;
    private String insuredId;
    private String sessionId;
    private String eventId;
    private Instant eventTime;
    private String anomalyTier;
    private String anomalyType;
    private Double anomalyScore;
    private Double typeConfidence;
    private String ruleType;
    private String eventJson;
    private Instant detectedAt;
}
