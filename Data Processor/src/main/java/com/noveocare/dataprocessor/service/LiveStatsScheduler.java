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

    @Scheduled(fixedRateString = "${app.scheduling.live-stats-fixed-rate-ms}")
    public void refresh() {
        try {
            // Delegate the actual aggregation logic to the statistics service.
            statisticsService.refreshLiveStatsSnapshot();
            dashboardSnapshotService.refreshAll();
        } catch (Exception e) {
            log.error("Failed to refresh live stats snapshot", e);
        }
    }
}
