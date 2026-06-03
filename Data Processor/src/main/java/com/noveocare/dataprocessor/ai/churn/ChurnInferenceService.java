package com.noveocare.dataprocessor.ai.churn;

import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionSummary;

import java.util.List;

public interface ChurnInferenceService {
    ChurnPrediction predict(SessionSummary summary, List<AuditTrailEvent> events, boolean anomalousUser);
    boolean isAvailable();
    String modelName();
    String artifactName();
}
