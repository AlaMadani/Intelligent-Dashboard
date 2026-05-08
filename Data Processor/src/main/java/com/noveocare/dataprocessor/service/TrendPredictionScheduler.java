package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.ai.ForecastSeriesPoint;
import com.noveocare.dataprocessor.ai.RuntimeArtifactService;
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

    private final RuntimeArtifactService runtimeArtifactService;
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
        log.debug("Forecast snapshot refreshed for {} series", runtimeArtifactService.getForecastSeries().size());
    }

    private void evaluateSystemTrafficAnomaly(LocalDate referenceDate) {
        List<ForecastSeriesPoint> totalEventsSeries =
                runtimeArtifactService.getForecastSeries().getOrDefault("total_events", List.of());
        if (totalEventsSeries.isEmpty()) {
            return;
        }

        ForecastSeriesPoint baseline = resolveForecastPoint(totalEventsSeries, referenceDate);
        if (baseline == null || baseline.getYhatUpper() == null) {
            return;
        }

        Instant now = Instant.now();
        long actual = statisticsService.countEventsForDate(referenceDate);
        double hoursElapsed = Math.max(1.0, now.atZone(ZoneOffset.UTC).getHour()
                + now.atZone(ZoneOffset.UTC).getMinute() / 60.0);
        double projectedDaily = actual / hoursElapsed * 24.0;
        if (hoursElapsed < 4.0 && actual < baseline.getYhatUpper() / 2.0) {
            return;
        }
        if (projectedDaily <= baseline.getYhatUpper()) {
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

    private ForecastSeriesPoint resolveForecastPoint(List<ForecastSeriesPoint> points, LocalDate referenceDate) {
        if (points == null || points.isEmpty() || referenceDate == null) {
            return null;
        }
        String targetDate = referenceDate.toString();
        for (ForecastSeriesPoint point : points) {
            if (targetDate.equals(shiftPointDate(point.getDs(), referenceDate.getYear()))) {
                return ForecastSeriesPoint.builder()
                        .ds(targetDate)
                        .yhat(point.getYhat())
                        .yhatLower(point.getYhatLower())
                        .yhatUpper(point.getYhatUpper())
                        .trend(point.getTrend())
                        .build();
            }
        }
        ForecastSeriesPoint fallback = points.get(points.size() - 1);
        return ForecastSeriesPoint.builder()
                .ds(targetDate)
                .yhat(fallback.getYhat())
                .yhatLower(fallback.getYhatLower())
                .yhatUpper(fallback.getYhatUpper())
                .trend(fallback.getTrend())
                .build();
    }

    private String shiftPointDate(String value, int year) {
        try {
            return LocalDate.parse(value).withYear(year).toString();
        } catch (Exception ex) {
            return value;
        }
    }
}
