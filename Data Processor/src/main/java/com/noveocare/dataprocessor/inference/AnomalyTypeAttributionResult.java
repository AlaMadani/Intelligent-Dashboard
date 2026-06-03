package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder(toBuilder = true)
public class AnomalyTypeAttributionResult {
    String anomalyType;
    String anomalyTypeSource;
    double anomalyTypeConfidence;
    List<String> candidateTypes;
    Map<String, Object> anomalyTypeEvidenceJson;
}
