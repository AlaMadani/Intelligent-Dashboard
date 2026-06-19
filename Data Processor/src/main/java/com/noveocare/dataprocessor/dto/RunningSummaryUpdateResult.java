package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RunningSummaryUpdateResult {
    SessionRunningSummary summary;
    AuditTrailEvent enrichedEvent;
}