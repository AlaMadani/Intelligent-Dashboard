package com.noveocare.dataprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
/**
 * Lightweight in-memory state tracking whether a session is still active or has ended.
 */
public class SessionState {
    private String sessionId;
    private String insuredId;
    private Instant firstEventTimestamp;
    private Instant lastEventTimestamp;
    private Instant lastEventIngestedAt;
    private int eventCount;
    /* True when a logout / session-end action has been received. */
    private boolean endedExplicitly;
    private String endReason;
    private Instant endedAt;
    /* True once the session has been fully processed, persisted, and cleaned up. */
    private boolean finalized;
}