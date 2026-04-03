package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * API projection for a persisted anomaly event, including the raw payload
 * captured by the detection pipeline.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyEventDto {
    /* Entity identity plus user/session correlation keys. */
    private Long id;
    private String insuredId;
    private String sessionId;
    private String eventId;

    /* Detection metadata displayed in the dashboard. */
    private Instant eventTime;
    private String anomalyTier;
    private String anomalyType;
    private Double anomalyScore;
    private Double typeConfidence;
    private String ruleType;

    /* Serialized event payload and persistence timestamp. */
    private String eventJson;
    private Instant detectedAt;
}
