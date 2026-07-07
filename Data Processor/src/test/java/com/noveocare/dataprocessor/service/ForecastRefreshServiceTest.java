package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.ai.forecast.ForecastPrediction;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.inference.ModelHealthService;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import com.noveocare.dataprocessor.service.StatisticsService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastRefreshServiceTest {

    private final AiForecastProperties forecastProperties = new AiForecastProperties();
    private final RedisCacheProperties cacheProperties = new RedisCacheProperties();

    {
        forecastProperties.setEnabled(true);
        forecastProperties.setAsyncRefreshEnabled(true);
        forecastProperties.setMinRefreshGapSeconds(0);
        forecastProperties.setWriteRedisSnapshot(true);
        forecastProperties.setWriteSqlSnapshot(true);
        cacheProperties.setForecast(Duration.ofHours(24));
    }

    @Test
    void markDirtySetsDirtyFlag() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, mock(ModelHealthService.class),
                mock(RedisCacheService.class), cacheProperties, mock(DashboardSnapshotPersistenceService.class), mock(StatisticsService.class));

        assertThat(service.isDirty()).isFalse();
        service.markDirty();
        assertThat(service.isDirty()).isTrue();
    }

    @Test
    void skipWhenNotDirty() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, mock(ModelHealthService.class),
                mock(RedisCacheService.class), cacheProperties, mock(DashboardSnapshotPersistenceService.class), mock(StatisticsService.class));

        boolean ran = service.runForecastIfNeeded();

        assertThat(ran).isFalse();
        verify(runtimeService, never()).forecast(any());
    }

    @Test
    void skipWhenAsyncDisabled() {
        AiForecastProps disabled = new AiForecastProps();
        disabled.setEnabled(true);
        disabled.setAsyncRefreshEnabled(false);
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, disabled, mock(ModelHealthService.class),
                mock(RedisCacheService.class), cacheProperties, mock(DashboardSnapshotPersistenceService.class), mock(StatisticsService.class));

        service.markDirty();
        boolean ran = service.runForecastIfNeeded();

        assertThat(ran).isFalse();
        verify(runtimeService, never()).forecast(any());
    }

    @Test
    void skipWhenMinGapNotElapsed() {
        AiForecastProps gap = new AiForecastProps();
        gap.setEnabled(true);
        gap.setAsyncRefreshEnabled(true);
        gap.setMinRefreshGapSeconds(9999);
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(ForecastPrediction.builder().warnings(List.of()).build());
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, gap, health, redis, cacheProperties, sql, mock(StatisticsService.class));

        service.markDirty();
        service.runForecastIfNeeded(); // first run succeeds

        service.markDirty();
        boolean ran = service.runForecastIfNeeded(); // second run should be skipped

        assertThat(ran).isFalse();
    }

    @Test
    void runsForecastAndPersists() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(ForecastPrediction.builder()
                .forecastDate(LocalDate.now())
                .anomalyRateForecast(0.0123)
                .totalEventsForecast(12345.0)
                .expectedAlertVolume(152.0)
                .anomalyRateModelName("Ridge")
                .totalEventsModelName("XGBoost")
                .warnings(List.of())
                .build());
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, mock(StatisticsService.class));

        service.markDirty();
        boolean ran = service.runForecastIfNeeded();

        assertThat(ran).isTrue();
        verify(redis).setJson(eq(CacheKeys.forecastDashboardV36Key()), any(Map.class), eq(cacheProperties.getForecast()));
        verify(sql).persistSnapshot(eq("forecast-dashboard"), eq("forecast-dashboard:latest"), any(Map.class), eq("forecast_refresh"));
        // Security overview forecast fields must be updated alongside dashboard
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), any(Map.class), eq(cacheProperties.getDashboard()));
        verify(sql, atLeastOnce()).persistSnapshot(eq("security-overview"), eq("security-overview:latest"), any(Map.class), eq("forecast_refresh"));
        verify(health).recordRuntimeSuccess("forecast_ridge");
        verify(health).recordRuntimeSuccess("forecast_xgboost");
    }

    @Test
    void forecastRefreshUpdatesSecurityOverviewForecast() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(ForecastPrediction.builder()
                .forecastDate(LocalDate.of(2026, 6, 21))
                .anomalyRateForecast(0.03805)
                .totalEventsForecast(1132.0)
                .expectedAlertVolume(43.0)
                .anomalyRateModelName("Ridge")
                .totalEventsModelName("XGBoost")
                .warnings(List.of())
                .build());
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, mock(StatisticsService.class));

        service.markDirty();
        service.runForecastIfNeeded();

        @SuppressWarnings("unchecked")
        var forecastCaptor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.forecastDashboardV36Key()), forecastCaptor.capture(), any());
        Map<String, Object> forecastPayload = forecastCaptor.getValue();

        @SuppressWarnings("unchecked")
        var securityCaptor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), securityCaptor.capture(), any());
        Map<String, Object> securityPayload = securityCaptor.getValue();

        // Security overview forecast fields must equal forecast dashboard values
        assertThat(securityPayload)
                .containsEntry("predictedAnomalyRateTomorrow", forecastPayload.get("predictedAnomalyRate"))
                .containsEntry("predictedTotalEventsTomorrow", forecastPayload.get("predictedTotalEvents"))
                .containsEntry("expectedAlertVolumeTomorrow", forecastPayload.get("expectedAlertVolume"));
        assertThat(securityPayload)
                .containsEntry("predictedAnomalyRateTomorrow", 0.03805)
                .containsEntry("predictedTotalEventsTomorrow", 1132.0)
                .containsEntry("expectedAlertVolumeTomorrow", 43.0);
        assertThat(securityPayload).doesNotContainKey("forecastWarnings");
    }

    @Test
    void forecastRefreshWithNullPredictionDoesNotWriteZero() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(ForecastPrediction.builder()
                .forecastDate(LocalDate.now())
                .anomalyRateForecast(null)
                .totalEventsForecast(null)
                .expectedAlertVolume(null)
                .anomalyRateModelName("Ridge")
                .totalEventsModelName("XGBoost")
                .warnings(List.of("forecast_insufficient_history"))
                .build());
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, mock(StatisticsService.class));

        service.markDirty();
        service.runForecastIfNeeded();

        @SuppressWarnings("unchecked")
        var securityCaptor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.securityOverviewDashboardKey()), securityCaptor.capture(), any());
        Map<String, Object> securityPayload = securityCaptor.getValue();

        // Must use null, not zero, when prediction is null
        assertThat(securityPayload)
                .containsEntry("predictedAnomalyRateTomorrow", null)
                .containsEntry("predictedTotalEventsTomorrow", null)
                .containsEntry("expectedAlertVolumeTomorrow", null);
        // Forecast warnings must be propagated
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) securityPayload.get("forecastWarnings");
        assertThat(warnings).contains("forecast_insufficient_history");
    }

    @Test
    void forecastRefreshIncludesNonEmptyHistoricalTotalEvents() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(ForecastPrediction.builder()
                .forecastDate(LocalDate.now())
                .anomalyRateForecast(0.0123)
                .totalEventsForecast(12345.0)
                .expectedAlertVolume(152.0)
                .anomalyRateModelName("Ridge")
                .totalEventsModelName("XGBoost")
                .warnings(List.of())
                .build());
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        StatisticsService stats = mock(StatisticsService.class);
        when(stats.countEventsForDate(any(LocalDate.class))).thenReturn(100L);
        when(stats.countAlertsForDate(any(LocalDate.class))).thenReturn(5L);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, stats);

        service.markDirty();
        service.runForecastIfNeeded();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.forecastDashboardV36Key()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) payload.get("historicalTotalEvents");
        assertThat(history).isNotEmpty();
        for (Map<String, Object> point : history) {
            assertThat(point).containsKey("date");
            assertThat(point).containsKey("value");
            assertThat(point.get("value")).isInstanceOf(Number.class);
            assertThat(((Number) point.get("value")).longValue()).isEqualTo(100L);
        }
    }

    @Test
    void forecastRefreshIncludesNonEmptyHistoricalAnomalyRate() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(ForecastPrediction.builder()
                .forecastDate(LocalDate.now())
                .anomalyRateForecast(0.0123)
                .totalEventsForecast(12345.0)
                .expectedAlertVolume(152.0)
                .anomalyRateModelName("Ridge")
                .totalEventsModelName("XGBoost")
                .warnings(List.of())
                .build());
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        StatisticsService stats = mock(StatisticsService.class);
        when(stats.countEventsForDate(any(LocalDate.class))).thenReturn(200L);
        when(stats.countAlertsForDate(any(LocalDate.class))).thenReturn(8L);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, stats);

        service.markDirty();
        service.runForecastIfNeeded();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.forecastDashboardV36Key()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) payload.get("historicalAnomalyRate");
        assertThat(history).isNotEmpty();
        for (Map<String, Object> point : history) {
            assertThat(point).containsKey("date");
            assertThat(point).containsKey("value");
            Object value = point.get("value");
            assertThat(value).isInstanceOf(Number.class);
            // Anomaly rate must be decimal, not percent string
            double rate = ((Number) value).doubleValue();
            assertThat(rate).isEqualTo(8.0 / 200.0);
        }
    }

    @Test
    void forecastRefreshWithEmptyHistoryProducesWarning() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(ForecastPrediction.builder()
                .forecastDate(LocalDate.now())
                .anomalyRateForecast(0.0123)
                .totalEventsForecast(12345.0)
                .expectedAlertVolume(152.0)
                .anomalyRateModelName("Ridge")
                .totalEventsModelName("XGBoost")
                .warnings(List.of())
                .build());
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        StatisticsService stats = mock(StatisticsService.class);
        // Return 0 for all history queries to simulate no data
        when(stats.countEventsForDate(any(LocalDate.class))).thenReturn(0L);
        when(stats.countAlertsForDate(any(LocalDate.class))).thenReturn(0L);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, stats);

        service.markDirty();
        service.runForecastIfNeeded();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.forecastDashboardV36Key()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> totalEvents = (List<Map<String, Object>>) payload.get("historicalTotalEvents");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anomalyRate = (List<Map<String, Object>>) payload.get("historicalAnomalyRate");
        assertThat(totalEvents).isEmpty();
        assertThat(anomalyRate).isEmpty();
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) payload.get("forecastWarnings");
        assertThat(warnings).contains("forecast_history_unavailable");
    }

    @Test
    void historicalAnomalyRateUsesDecimalNotPercent() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(ForecastPrediction.builder()
                .forecastDate(LocalDate.now())
                .anomalyRateForecast(0.0123)
                .totalEventsForecast(12345.0)
                .expectedAlertVolume(152.0)
                .anomalyRateModelName("Ridge")
                .totalEventsModelName("XGBoost")
                .warnings(List.of())
                .build());
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        StatisticsService stats = mock(StatisticsService.class);
        // 50 events, 2 alerts -> anomaly rate = 0.04
        when(stats.countEventsForDate(any(LocalDate.class))).thenReturn(50L);
        when(stats.countAlertsForDate(any(LocalDate.class))).thenReturn(2L);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, stats);

        service.markDirty();
        service.runForecastIfNeeded();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.forecastDashboardV36Key()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) payload.get("historicalAnomalyRate");
        assertThat(history).isNotEmpty();
        for (Map<String, Object> point : history) {
            Object value = point.get("value");
            assertThat(value).isInstanceOf(Number.class);
            // Must be decimal (0.04), not percent string ("4%")
            double rate = ((Number) value).doubleValue();
            assertThat(rate).isEqualTo(0.04);
            assertThat(rate).isNotEqualTo(4.0);
            assertThat(rate).isNotEqualTo("4%");
        }
    }

    @Test
    void recordsErrorOnForecastFailure() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenThrow(new RuntimeException("model_unavailable"));
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, mock(StatisticsService.class));

        service.markDirty();
        boolean ran = service.runForecastIfNeeded();

        assertThat(ran).isTrue();
        verify(health).recordRuntimeError(eq("forecast_ridge"), any());
        verify(health).recordRuntimeError(eq("forecast_xgboost"), any());
    }

    @Test
    void snapshotPayloadContainsExpectedKeys() {
        ForecastPrediction prediction = ForecastPrediction.builder()
                .forecastDate(LocalDate.of(2026, 6, 19))
                .anomalyRateForecast(0.0123)
                .totalEventsForecast(12345.0)
                .expectedAlertVolume(152.0)
                .anomalyRateModelName("Ridge")
                .totalEventsModelName("XGBoost")
                .warnings(List.of())
                .build();
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(prediction);
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, mock(StatisticsService.class));

        service.markDirty();
        service.runForecastIfNeeded();

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.forecastDashboardV36Key()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload)
                .containsEntry("schemaVersion", "v3.6.1")
                .containsEntry("predictedAnomalyRate", 0.0123)
                .containsEntry("predictedTotalEvents", 12345.0)
                .containsEntry("expectedAlertVolume", 152.0)
                .containsEntry("anomalyRateModel", "Ridge")
                .containsEntry("totalEventsModel", "XGBoost")
                .containsKey("forecastDate")
                .containsKey("source");
    }

    @Test
    void insufficientHistoryWarning() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.forecast(any())).thenReturn(ForecastPrediction.builder()
                .forecastDate(LocalDate.now())
                .anomalyRateForecast(null)
                .totalEventsForecast(null)
                .expectedAlertVolume(null)
                .anomalyRateModelName("Ridge")
                .totalEventsModelName("XGBoost")
                .warnings(List.of("forecast_insufficient_history"))
                .build());
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        RedisCacheService redis = mock(RedisCacheService.class);
        ModelHealthService health = mock(ModelHealthService.class);
        DashboardSnapshotPersistenceService sql = mock(DashboardSnapshotPersistenceService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, health, redis, cacheProperties, sql, mock(StatisticsService.class));

        service.markDirty();
        boolean ran = service.runForecastIfNeeded();

        assertThat(ran).isTrue();
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(redis, atLeastOnce()).setJson(eq(CacheKeys.forecastDashboardV36Key()), captor.capture(), any());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsEntry("predictedAnomalyRate", null);
        assertThat(payload).containsEntry("predictedTotalEvents", null);
        assertThat(payload).containsEntry("expectedAlertVolume", null);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) payload.get("forecastWarnings");
        assertThat(warnings).contains("forecast_insufficient_history");
    }

    @Test
    void diagnosticsExposesState() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        when(runtimeService.ridgeLoaded()).thenReturn(true);
        when(runtimeService.xgboostLoaded()).thenReturn(true);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, mock(ModelHealthService.class),
                mock(RedisCacheService.class), cacheProperties, mock(DashboardSnapshotPersistenceService.class), mock(StatisticsService.class));

        service.markDirty();
        Map<String, Object> diag = service.diagnosticsSnapshot();
        assertThat(diag)
                .containsEntry("dirty", true)
                .containsEntry("asyncRefreshEnabled", true)
                .containsEntry("ridgeLoaded", true)
                .containsEntry("xgboostLoaded", true);
    }

    @Test
    void listenerDoesNotRunForecastWhenAsyncEnabled() {
        ForecastRuntimeService runtimeService = mock(ForecastRuntimeService.class);
        ForecastRefreshService service = new ForecastRefreshService(
                runtimeService, forecastProperties, mock(ModelHealthService.class),
                mock(RedisCacheService.class), cacheProperties, mock(DashboardSnapshotPersistenceService.class), mock(StatisticsService.class));

        service.markDirty();
        verify(runtimeService, never()).forecast(any());
    }

    private static class AiForecastProps extends AiForecastProperties {
    }
}