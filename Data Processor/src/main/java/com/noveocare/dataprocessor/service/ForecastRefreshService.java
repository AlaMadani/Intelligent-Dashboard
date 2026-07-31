package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.ai.forecast.ForecastPrediction;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.inference.ModelHealthService;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computes and caches anomaly-rate and total-events forecasts, updates the security
 * overview dashboard with forecast fields, and records runtime model health.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ForecastRefreshService {

    /* Injected dependencies */
    private final ForecastRuntimeService forecastRuntimeService;
    private final AiForecastProperties forecastProperties;
    private final ModelHealthService modelHealthService;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;
    private final DashboardSnapshotPersistenceService snapshotPersistenceService;
    private final StatisticsService statisticsService;

    /* Forecast state and metrics */
    private final AtomicBoolean forecastDirty = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastForecastRunAt = new AtomicReference<>();
    private final AtomicLong forecastRefreshRunCount = new AtomicLong();
    private final AtomicLong forecastMsTotal = new AtomicLong();

    /* --- Dirty-flag management --- */

    public void markDirty() {
        forecastDirty.set(true);
    }

    public boolean isDirty() {
        return forecastDirty.get();
    }

    /* --- Forecast execution --- */

    public boolean runForecastIfNeeded() {
        if (!forecastProperties.isEnabled()) {
            return false;
        }
        if (!forecastProperties.isAsyncRefreshEnabled()) {
            return false;
        }
        if (!forecastDirty.get()) {
            log.info("FORECAST_REFRESH_SKIPPED reason=not_dirty");
            return false;
        }
        Instant last = lastForecastRunAt.get();
        if (last != null) {
            long gapSec = java.time.Duration.between(last, Instant.now()).getSeconds();
            if (gapSec < forecastProperties.getMinRefreshGapSeconds()) {
                log.info("FORECAST_REFRESH_SKIPPED reason=min_gap gapSec={} minGapSec={}", gapSec, forecastProperties.getMinRefreshGapSeconds());
                return false;
            }
        }
        runForecast();
        return true;
    }

    public void runForecast() {
        long startMs = System.currentTimeMillis();
        log.info("FORECAST_REFRESH_STARTED reason=dirty scheduled=true");
        try {
            LocalDate date = LocalDate.now(ZoneOffset.UTC);
            ForecastPrediction prediction = forecastRuntimeService.forecast(date);
            long elapsedMs = System.currentTimeMillis() - startMs;

            Map<String, Object> snapshot = buildSnapshotPayload(date, prediction);
            if (forecastProperties.isWriteRedisSnapshot()) {
                redisCacheService.setJson(CacheKeys.forecastDashboardV36Key(), snapshot, cacheProperties.getForecast());
            }
            if (forecastProperties.isWriteSqlSnapshot()) {
                snapshotPersistenceService.persistSnapshot("forecast-dashboard", "forecast-dashboard:latest", snapshot, "forecast_refresh");
            }
            updateSecurityOverviewForecast(prediction);
            updateRuntimeHealth(prediction);

            forecastDirty.set(false);
            lastForecastRunAt.set(Instant.now());
            forecastRefreshRunCount.incrementAndGet();
            forecastMsTotal.addAndGet(elapsedMs);

            log.info("FORECAST_REFRESH_COMPLETED predictedAnomalyRate={} predictedTotalEvents={} expectedAlertVolume={} elapsedMs={}",
                    prediction.getAnomalyRateForecast(), prediction.getTotalEventsForecast(), prediction.getExpectedAlertVolume(), elapsedMs);
        } catch (Exception ex) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.warn("FORECAST_REFRESH_FAILED errorClass={} message={} elapsedMs={}",
                    ex.getClass().getSimpleName(), ex.getMessage(), elapsedMs, ex);
            modelHealthService.recordRuntimeError("forecast_ridge", "forecast_refresh_error: " + ex.getMessage());
            modelHealthService.recordRuntimeError("forecast_xgboost", "forecast_refresh_error: " + ex.getMessage());
        }
    }

    /* --- Payload builders --- */

    private Map<String, Object> buildSnapshotPayload(LocalDate referenceDate, ForecastPrediction prediction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "v3.6.1");
        payload.put("forecastDate", prediction.getForecastDate() != null ? prediction.getForecastDate().toString() : null);
        payload.put("predictedAnomalyRate", prediction.getAnomalyRateForecast());
        payload.put("predictedTotalEvents", prediction.getTotalEventsForecast());
        payload.put("expectedAlertVolume", prediction.getExpectedAlertVolume());
        payload.put("anomalyRateModel", prediction.getAnomalyRateModelName());
        payload.put("totalEventsModel", prediction.getTotalEventsModelName());
        payload.put("forecastWarnings", prediction.getWarnings());
        payload.put("source", "redis");

        List<Map<String, Object>> historicalTotalEvents = new ArrayList<>();
        List<Map<String, Object>> historicalAnomalyRate = new ArrayList<>();
        List<String> historyWarnings = buildHistoricalSeries(referenceDate, historicalTotalEvents, historicalAnomalyRate);
        payload.put("historicalTotalEvents", historicalTotalEvents);
        payload.put("historicalAnomalyRate", historicalAnomalyRate);
        if (!historyWarnings.isEmpty()) {
            List<String> existingWarnings = prediction.getWarnings();
            if (existingWarnings != null && !existingWarnings.isEmpty()) {
                List<String> merged = new ArrayList<>(existingWarnings);
                merged.addAll(historyWarnings);
                payload.put("forecastWarnings", merged);
            } else {
                payload.put("forecastWarnings", historyWarnings);
            }
        }
        return payload;
    }

    /* Builds 7-day historical series for total events and anomaly rate */
    private List<String> buildHistoricalSeries(LocalDate referenceDate,
                                                List<Map<String, Object>> totalEventsOut,
                                                List<Map<String, Object>> anomalyRateOut) {
        List<String> warnings = new ArrayList<>();
        boolean anyData = false;
        for (int i = 7; i >= 1; i--) {
            LocalDate date = referenceDate.minusDays(i);
            long events = statisticsService.countEventsForDate(date);
            long alerts = statisticsService.countAlertsForDate(date);
            if (events > 0) {
                anyData = true;
                Map<String, Object> eventPoint = new LinkedHashMap<>();
                eventPoint.put("date", date.toString());
                eventPoint.put("value", events);
                totalEventsOut.add(eventPoint);

                Map<String, Object> anomalyPoint = new LinkedHashMap<>();
                anomalyPoint.put("date", date.toString());
                anomalyPoint.put("value", (double) alerts / events);
                anomalyRateOut.add(anomalyPoint);
            }
        }
        if (!anyData) {
            warnings.add("forecast_history_unavailable");
        } else if (totalEventsOut.size() < 7) {
            warnings.add("forecast_history_partial");
        }
        return warnings;
    }

    /* --- Side-effect updates --- */

    private void updateRuntimeHealth(ForecastPrediction prediction) {
        boolean ridgeOk = forecastRuntimeService.ridgeLoaded()
                && prediction != null
                && !"fallback".equalsIgnoreCase(prediction.getAnomalyRateModelName());
        boolean xgbOk = forecastRuntimeService.xgboostLoaded()
                && prediction != null
                && !"fallback".equalsIgnoreCase(prediction.getTotalEventsModelName());

        if (ridgeOk) {
            modelHealthService.recordRuntimeSuccess("forecast_ridge");
        } else {
            modelHealthService.recordRuntimeError("forecast_ridge",
                    firstWarning(prediction == null ? null : prediction.getWarnings(), "forecast_ridge_unavailable"));
        }
        if (xgbOk) {
            modelHealthService.recordRuntimeSuccess("forecast_xgboost");
        } else {
            modelHealthService.recordRuntimeError("forecast_xgboost",
                    firstWarning(prediction == null ? null : prediction.getWarnings(), "forecast_xgboost_unavailable"));
        }
    }

    /* Updates the security overview dashboard with forecast predictions */
    private void updateSecurityOverviewForecast(ForecastPrediction prediction) {
        try {
            Map<String, Object> securityOverview = redisCacheService.getJson(CacheKeys.securityOverviewDashboardKey(), Map.class);
            if (securityOverview == null) {
                securityOverview = new LinkedHashMap<>();
                securityOverview.put("schemaVersion", "v3.6.1");
                securityOverview.put("snapshotTimestamp", Instant.now().toString());
            }
            securityOverview.put("predictedAnomalyRateTomorrow", prediction.getAnomalyRateForecast());
            securityOverview.put("predictedTotalEventsTomorrow", prediction.getTotalEventsForecast());
            securityOverview.put("expectedAlertVolumeTomorrow", prediction.getExpectedAlertVolume());
            securityOverview.put("forecastDate", prediction.getForecastDate() != null ? prediction.getForecastDate().toString() : null);
            List<String> warnings = prediction.getWarnings();
            if (warnings != null && !warnings.isEmpty()) {
                securityOverview.put("forecastWarnings", warnings);
            } else {
                securityOverview.remove("forecastWarnings");
            }
            if (forecastProperties.isWriteRedisSnapshot()) {
                redisCacheService.setJson(CacheKeys.securityOverviewDashboardKey(), securityOverview, cacheProperties.getDashboard());
            }
            if (forecastProperties.isWriteSqlSnapshot()) {
                snapshotPersistenceService.persistSnapshot("security-overview", "security-overview:latest", securityOverview, "forecast_refresh");
            }
            log.debug("SECURITY_OVERVIEW_FORECAST_UPDATED");
        } catch (Exception ex) {
            log.warn("SECURITY_OVERVIEW_FORECAST_UPDATE_FAILED errorClass={} message={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    /* --- Diagnostics --- */

    public Map<String, Object> diagnosticsSnapshot() {
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("dirty", forecastDirty.get());
        diag.put("lastForecastRunAt", lastForecastRunAt.get() != null ? lastForecastRunAt.get().toString() : null);
        diag.put("forecastRefreshRunCount", forecastRefreshRunCount.get());
        diag.put("forecastMsAvg", forecastRefreshRunCount.get() > 0
                ? (double) forecastMsTotal.get() / forecastRefreshRunCount.get() : null);
        diag.put("asyncRefreshEnabled", forecastProperties.isAsyncRefreshEnabled());
        diag.put("refreshIntervalSeconds", forecastProperties.getRefreshIntervalSeconds());
        diag.put("minRefreshGapSeconds", forecastProperties.getMinRefreshGapSeconds());
        diag.put("ridgeLoaded", forecastRuntimeService.ridgeLoaded());
        diag.put("xgboostLoaded", forecastRuntimeService.xgboostLoaded());
        return diag;
    }

    /* Returns the first non-blank warning or the fallback */
    private static String firstWarning(List<String> warnings, String fallback) {
        if (warnings != null && !warnings.isEmpty()) {
            String first = null;
            for (String w : warnings) {
                if (w != null && !w.isBlank()) {
                    first = w;
                    break;
                }
            }
            if (first != null) return first;
        }
        return fallback;
    }
}