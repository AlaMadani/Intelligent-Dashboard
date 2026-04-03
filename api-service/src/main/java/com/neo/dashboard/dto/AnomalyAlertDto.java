package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Lightweight DTO matching anomaly alert payloads published by the data
 * processor and reused by the active-anomaly endpoint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnomalyAlertDto {
    /* Correlation keys used to link the alert back to persisted data. */
    private String insuredId;
    private String sessionId;
    private String eventId;

    /* Alert classification and scoring fields sent by the detector. */
    private String anomalyTier;
    private String anomalyType;
    private Double anomalyScore;
    private Double typeConfidence;
    private String ruleType;

    /* Event occurrence time plus alert emission time. */
    private Instant eventTime;
    private Instant detectedAt;
}
