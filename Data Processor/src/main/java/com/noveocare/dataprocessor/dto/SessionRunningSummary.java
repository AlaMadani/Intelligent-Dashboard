package com.noveocare.dataprocessor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * Mutable running aggregate maintained in Redis for every live session.
 */
public class SessionRunningSummary {
    /* Session and user identifiers. */
    private String sessionId;
    private String insuredId;

    /* First-event snapshot. */
    private Instant firstTimestamp;
    private Integer firstSequenceInSession;
    private String firstAction;
    private String firstPage;
    private String firstIp;
    private String firstDevice;
    private String firstCountry;
    private String firstStatus;

    /* Last-event snapshot. */
    private Instant lastTimestamp;
    private Integer lastSequenceInSession;
    private String lastAction;
    private String lastPage;
    private String lastStatus;
    private String lastIp;
    private String lastDevice;
    private String lastCountry;

    /* Event counts and outcome tallies. */
    private int eventCount;
    private int successCount;
    private int failureCount;
    private int consecutiveFailureCount;
    private int maxConsecutiveFailureCount;

    /* Uniqueness counters for pages, actions, devices, countries, IPs, routes. */
    private int uniquePagesCount;
    private int uniqueActionsCount;
    private int uniqueDevicesCount;
    private int uniqueCountriesCount;
    private int uniqueIpsCount;
    private int uniqueRoutesCount;

    /* Request/response byte totals. */
    private long totalRequestBytes;
    private long totalResponseBytes;

    /* Inter-action timing statistics. */
    private long sumInterActionMs;
    private long maxInterActionMs;
    private long minInterActionMs;
    private int eventCountWithInterAction;

    /* Temporal context counters. */
    private int businessHoursCount;
    private int weekendCount;

    /* Suspicious-behavior flags. */
    private int geoJumpDetected;
    private int ipChangedDetected;
    private int deviceChangedDetected;
    private int skipLoginDetected;

    /* Download activity. */
    private int totalDownloadActions;
    private int maxDownloadsIn2Minutes;
    private int pingPongCount;

    /* Login/logout tracking. */
    private int hasLogin;
    private int hasLogout;

    /* KO (knock-out / failure) streak tracking. */
    private int cumulativeKOs;
    private int currentKoStreak;
    private int longestKoStreak;

    private int hasLoggedIn;

    /* Additional inter-action duration aggregation. */
    private long totalInterActionSeconds;
    private int interActionCount;

    /* Rolling risk-score statistics. */
    private double riskScoreSum;
    private int riskScoreCount;
    private double riskScoreMax;

    /* Anomaly and abrupt-end markers. */
    private int anomalyEventCount;
    private int endedAbruptly;
}