package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.ai.forecast.ForecastPrediction;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RedisPubSubProperties;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.kafka.AlertPublisher;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class TrendPredictionScheduler {

    private final ForecastRuntimeService forecastRuntimeService;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;
    private final RedisPubSubProperties pubSubProperties;
    private final StatisticsService statisticsService;
    private final AlertPublisher alertPublisher;
    private final DashboardSnapshotService dashboardSnapshotService;

    @Scheduled(cron = "${app.scheduling.trend-cron}")
    public void refreshForecastSnapshot() {
        LocalDate referenceDate = LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> payload = dashboardSnapshotService.buildForecastSnapshot(referenceDate);
        redisCacheService.setJson(CacheKeys.dashboardKey("forecast-series"), payload, cacheProperties.getForecast());
        redisCacheService.setJson(CacheKeys.dashboardKey("forecasts"), payload, cacheProperties.getForecast());
        redisCacheService.setJson(CacheKeys.trendStatsKey(referenceDate.toString()), payload, cacheProperties.getForecast());
        redisCacheService.publishJson(pubSubProperties.getLiveStatsChannel(), Map.of("refresh", "forecast-series"));
        redisCacheService.publishJson(pubSubProperties.getLiveStatsChannel(), Map.of("refresh", "forecasts"));
        evaluateSystemTrafficAnomaly(referenceDate);
        log.debug("Forecast snapshot refreshed");
    }

    private void evaluateSystemTrafficAnomaly(LocalDate referenceDate) {
        ForecastPrediction forecast = forecastRuntimeService.forecast(referenceDate);
        if (forecast.getTotalEventsForecast() == null || forecast.getTotalEventsForecast() <= 0.0) {
            return;
        }

        Instant now = Instant.now();
        long actual = statisticsService.countEventsForDate(referenceDate);
        double hoursElapsed = Math.max(1.0, now.atZone(ZoneOffset.UTC).getHour()
                + now.atZone(ZoneOffset.UTC).getMinute() / 60.0);
        double projectedDaily = actual / hoursElapsed * 24.0;
        double upperBound = forecast.getTotalEventsForecast() * 1.35;
        if (hoursElapsed < 4.0 && actual < upperBound / 2.0) {
            return;
        }
        if (projectedDaily <= upperBound) {
            return;
        }

        AnomalyAlert alert = AnomalyAlert.builder()
                .insuredId("SYSTEM")
                .sessionId("TRAFFIC-DAY-" + referenceDate)
                .anomalyTier("SYSTEM")
                .anomalyType("SYSTEM_TRAFFIC_ANOMALY")
                .anomalyFlag(true)
                .riskScore(90.0)
                .ruleType("forecast_upper_bounds_violation")
                .anomalyScore((double) actual)
                .anomalyProbability(1.0)
                .eventTime(Instant.now())
                .detectedAt(Instant.now())
                .build();
        alertPublisher.publish(alert, null);
    }
}
