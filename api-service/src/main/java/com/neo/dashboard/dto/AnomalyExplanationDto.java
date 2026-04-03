package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents the final anomaly explanation returned to the UI, regardless of
 * whether it came from Gemini or the local heuristic fallback.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyExplanationDto {
    /* Identity of the anomaly being explained plus explanation provenance. */
    private Long anomalyEventId;
    private String source;
    private String model;

    /* Generation metadata and the final markdown explanation body. */
    private Instant generatedAt;
    private Boolean cached;
    private String explanation;
}
