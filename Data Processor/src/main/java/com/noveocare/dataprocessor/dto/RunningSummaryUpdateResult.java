package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Tuple returned after updating the running session summary with a new event.
 */
@Value
@Builder
public class RunningSummaryUpdateResult {
    SessionRunningSummary summary;
    AuditTrailEvent enrichedEvent;
}