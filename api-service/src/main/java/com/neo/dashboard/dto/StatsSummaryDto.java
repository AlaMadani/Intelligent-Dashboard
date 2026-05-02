package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatsSummaryDto {
    private long totalSessions;
    private long totalAnomalies;
    private long anomalousSessions;
    private long activeSessionsNow;
    private long eventsToday;
    private double anomalyRate;
    private Map<String, Long> anomaliesByType;
    private Map<String, Long> usersByRiskTier;
    private Map<Integer, Long> sessionsByPersonaCluster;
    private Instant generatedAt;
}