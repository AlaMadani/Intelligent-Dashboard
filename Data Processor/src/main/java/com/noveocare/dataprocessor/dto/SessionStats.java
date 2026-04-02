package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Aggregated statistics derived from a fully reconstructed session.
 */
@Data
@Builder
public class SessionStats {
    // Timing and size metrics for the session as a whole.
    private long sessionDurationSeconds;
    private int sessionLength;
    private int uniqueActionCount;

    // Quality and diversity signals used by risk and analytics features.
    private double koRate;
    private double meanDeltaSeconds;
    private double actionDiversity;
    private Map<String, Long> actionCounts;
}
