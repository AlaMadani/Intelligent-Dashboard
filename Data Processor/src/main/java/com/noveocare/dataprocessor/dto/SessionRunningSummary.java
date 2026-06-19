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
public class SessionRunningSummary {
    private String sessionId;
    private String insuredId;

    private Instant firstTimestamp;
    private Integer firstSequenceInSession;
    private String firstAction;
    private String firstPage;
    private String firstIp;
    private String firstDevice;
    private String firstCountry;
    private String firstStatus;

    private Instant lastTimestamp;
    private Integer lastSequenceInSession;
    private String lastAction;
    private String lastPage;
    private String lastStatus;
    private String lastIp;
    private String lastDevice;
    private String lastCountry;

    private int eventCount;
    private int successCount;
    private int failureCount;
    private int consecutiveFailureCount;
    private int maxConsecutiveFailureCount;

    private int uniquePagesCount;
    private int uniqueActionsCount;
    private int uniqueDevicesCount;
    private int uniqueCountriesCount;
    private int uniqueIpsCount;
    private int uniqueRoutesCount;

    private long totalRequestBytes;
    private long totalResponseBytes;

    private long sumInterActionMs;
    private long maxInterActionMs;
    private long minInterActionMs;
    private int eventCountWithInterAction;

    private int businessHoursCount;
    private int weekendCount;

    private int geoJumpDetected;
    private int ipChangedDetected;
    private int deviceChangedDetected;
    private int skipLoginDetected;

    private int totalDownloadActions;
    private int maxDownloadsIn2Minutes;
    private int pingPongCount;

    private int hasLogin;
    private int hasLogout;

    private int cumulativeKOs;
    private int currentKoStreak;
    private int longestKoStreak;

    private int hasLoggedIn;

    private long totalInterActionSeconds;
    private int interActionCount;

    private double riskScoreSum;
    private int riskScoreCount;
    private double riskScoreMax;

    private int anomalyEventCount;
    private int endedAbruptly;
}