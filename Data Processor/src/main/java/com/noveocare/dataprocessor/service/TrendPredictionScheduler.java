package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.ai.DeploymentManifest;
import com.noveocare.dataprocessor.ai.ForecastSeriesPoint;
import com.noveocare.dataprocessor.ai.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.kafka.AlertPublisher;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class TrendPredictionScheduler {

    private final RuntimeArtifactService runtimeArtifactService;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;
    private final StatisticsService statisticsService;
    private final AlertPublisher alertPublisher;

    @Scheduled(cron = "${app.scheduling.trend-cron}")
    public void refreshForecastSnapshot() {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, DeploymentManifest.ForecastArtifact> entry
                : runtimeArtifactService.getDeploymentManifest().getForecasting().entrySet()) {
            payload.put(entry.getKey(), Map.of(
                    "points", runtimeArtifactService.getForecastSeries().getOrDefault(entry.getKey(), java.util.List.of()),
                    "mae", entry.getValue().getMae(),
                    "rmse", entry.getValue().getRmse()));
        }
        payload.put("generatedAt", Instant.now().toString());
        redisCacheService.setJson(CacheKeys.dashboardKey("forecast-series"), payload, cacheProperties.getForecast());
        evaluateSystemTrafficAnomaly();
        log.info("Forecast snapshot refreshed for {} series", runtimeArtifactService.getForecastSeries().size());
    }

    private void evaluateSystemTrafficAnomaly() {
        List<ForecastSeriesPoint> totalEventsSeries =
                runtimeArtifactService.getForecastSeries().getOrDefault("total_events", List.of());
        if (totalEventsSeries.isEmpty()) {
            return;
        }

        ForecastSeriesPoint latest = totalEventsSeries.get(totalEventsSeries.size() - 1);
        long actual = statisticsService.countEventsLastMinutes(5);
        double lower = latest.getYhatLower() == null ? 0.0 : latest.getYhatLower();
        double upper = latest.getYhatUpper() == null ? Double.MAX_VALUE : latest.getYhatUpper();
        if (actual >= lower && actual <= upper) {
            return;
        }

        AnomalyAlert alert = AnomalyAlert.builder()
                .insuredId("SYSTEM")
                .sessionId("TRAFFIC-WINDOW-5M")
                .anomalyTier("SYSTEM")
                .anomalyType("SYSTEM_TRAFFIC_ANOMALY")
                .anomalyFlag(true)
                .riskScore(90.0)
                .ruleType("forecast_bounds_violation")
                .anomalyScore((double) actual)
                .anomalyProbability(1.0)
                .eventTime(Instant.now())
                .detectedAt(Instant.now())
                .build();
        alertPublisher.publish(alert, null);
    }
}
