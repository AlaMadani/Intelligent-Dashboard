package com.noveocare.dataprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionState {
    private String sessionId;
    private String insuredId;
    private Instant firstEventTimestamp;
    private Instant lastEventTimestamp;
    private Instant lastEventIngestedAt;
    private int eventCount;
    private boolean endedExplicitly;
    private String endReason;
    private Instant endedAt;
    private boolean finalized;
}