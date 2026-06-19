package com.noveocare.dataprocessor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes the live dashboard snapshot stored in Redis.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveStatsScheduler {

    private final StatisticsService statisticsService;
    private final DashboardSnapshotService dashboardSnapshotService;

    @Scheduled(fixedRateString = "${app.scheduling.live-stats-fixed-rate-ms:5000}")
    public void refresh() {
        try {
            // Aggregate rolling metrics and refresh only the views that depend on in-flight sessions.
            statisticsService.refreshLiveStatsSnapshot();
            if (dashboardSnapshotService.isRiskySessionsDirty()) {
                dashboardSnapshotService.refreshRiskySessions();
            }
        } catch (Exception e) {
            log.error("Failed to refresh live stats snapshot", e);
        }
    }
}
