package com.noveocare.dataprocessor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class LiveStatsScheduler {

    private final StatisticsService statisticsService;

    @Scheduled(fixedRateString = "${app.scheduling.live-stats-fixed-rate-ms}")
    public void refresh() {
        try {
            statisticsService.refreshLiveStatsSnapshot();
        } catch (Exception e) {
            log.error("Failed to refresh live stats snapshot", e);
        }
    }
}
