package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class SessionStats {
    private long sessionDurationSeconds;
    private int sessionLength;
    private int uniqueActionCount;
    private double koRate;
    private double meanDeltaSeconds;
    private double actionDiversity;
    private Map<String, Long> actionCounts;
}
