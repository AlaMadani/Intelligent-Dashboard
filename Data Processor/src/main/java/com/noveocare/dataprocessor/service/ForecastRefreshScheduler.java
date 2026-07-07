package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.config.AiForecastProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ForecastRefreshScheduler {

    private final ForecastRefreshService forecastRefreshService;
    private final AiForecastProperties forecastProperties;

    @Scheduled(fixedRateString = "${app.ai.forecast.refresh-interval-seconds:60}000")
    public void refreshForecast() {
        if (!forecastProperties.isEnabled() || !forecastProperties.isAsyncRefreshEnabled()) {
            return;
        }
        forecastRefreshService.runForecastIfNeeded();
    }
}