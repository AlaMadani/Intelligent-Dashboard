package com.noveocare.dataprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Alert payload shared between the detection pipeline, persistence, and Kafka publishing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyAlert {
    // Event and session identifiers that let consumers correlate the alert back to source data.
    private String insuredId;
    private String sessionId;
    private String eventId;

    // Detection outputs coming from rules and ML models.
    private String anomalyTier;
    private String anomalyType;
    private Double anomalyScore;
    private Double typeConfidence;
    private String ruleType;

    // Timestamps for the triggering event and the actual detection moment.
    private Instant eventTime;
    private Instant detectedAt;
}
