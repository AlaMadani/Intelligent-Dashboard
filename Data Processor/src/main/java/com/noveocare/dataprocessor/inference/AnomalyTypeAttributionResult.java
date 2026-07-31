package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Holds the result of anomaly type attribution: the best-guess anomaly category,
 * its confidence, source, candidate alternatives, and supporting evidence.
 */
@Value
@Builder(toBuilder = true)
public class AnomalyTypeAttributionResult {

    /* ---- Attributed type fields ---- */
    String anomalyType;
    String anomalyTypeSource;
    double anomalyTypeConfidence;
    List<String> candidateTypes;
    Map<String, Object> anomalyTypeEvidenceJson;
}
