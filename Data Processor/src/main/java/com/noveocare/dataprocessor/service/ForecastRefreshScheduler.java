package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.config.AiForecastProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically triggers forecast refresh when the async refresh is enabled.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ForecastRefreshScheduler {

    /* Injected dependencies */
    private final ForecastRefreshService forecastRefreshService;
    private final AiForecastProperties forecastProperties;

    /* --- Scheduled task --- */

    @Scheduled(fixedRateString = "${app.ai.forecast.refresh-interval-seconds:60}000")
    public void refreshForecast() {
        if (!forecastProperties.isEnabled() || !forecastProperties.isAsyncRefreshEnabled()) {
            return;
        }
        forecastRefreshService.runForecastIfNeeded();
    }
}