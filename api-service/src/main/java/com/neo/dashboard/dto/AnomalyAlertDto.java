package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/* DTO matching AnomalyAlert payloads from data-processor. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnomalyAlertDto {
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
