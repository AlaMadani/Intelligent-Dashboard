package com.noveocare.dataprocessor.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)
public class SessionSummary {
    private String sessionId;
    private String insuredId;
    private String persona;
    private String countryCode;
    private String city;
    private String month;
    private Integer sessionNumber;
    private Instant sessionStart;
    private Instant sessionEnd;
    private Integer startHour;
    private Integer endHour;
    private Integer dayOfWeek;
    private Integer isWeekend;
    private String firstAction;
    private String lastAction;
    private String firstRoute;
    private String lastRoute;
    private Integer totalEvents;
    private Long totalDurationSeconds;
    private Double avgInterActionSeconds;
    private Double minInterActionSeconds;
    private Double maxInterActionSeconds;
    private Integer uniqueActions;
    private Integer uniqueRoutes;
    private Integer uniqueIpsUsed;
    private Integer uniqueDevicesUsed;
    private Integer totalKOs;
    private Integer totalOKs;
    private Integer longestKoStreak;
    private Integer hasLogin;
    private Integer hasLogout;
    private Integer ipChanged;
    private Integer deviceChanged;
    private Integer totalDownloadActions;
    private Integer maxDownloadsIn2Minutes;
    private Integer pingPongCount;
    private Double riskScoreMax;
    private Double riskScoreAvg;
    private Integer endedAbruptly;
    private Integer anomalyEventCount;
    private String primaryAnomalyType;
    private List<String> anomalyTypes;
    private List<String> campaignIds;
    private String actionSequenceSignature;
    private String routeSequenceSignature;
    private Map<String, Long> actionCounts;
}
