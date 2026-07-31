package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO holding the attributed anomaly type together with its confidence,
 * source label, and any supporting evidence.
 * <p>
 * Used by the investigation detail view to explain <em>why</em> a
 * particular anomaly category was assigned to an event.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36AnomalyTypeAttributionDto {
    /** The assigned anomaly type label (e.g. "behavioral", "velocity", "structural"). */
    private String anomalyType;
    /** Confidence score for the attribution (0.0 – 1.0). */
    @JsonAlias("anomalyTypeConfidence")
    private Double confidence;
    /** Source system or model that produced this attribution. */
    private String source;
    /** Free-form evidence map supporting the attribution decision. */
    private Map<String, Object> evidence;
}
