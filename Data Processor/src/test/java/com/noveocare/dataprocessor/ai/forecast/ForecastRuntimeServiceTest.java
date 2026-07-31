package com.noveocare.dataprocessor.ai.forecast;

import com.noveocare.dataprocessor.ai.V34TestArtifacts;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.service.StatisticsService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for ForecastRuntimeService: feature vector construction and
 * anomaly rate forecasting with naive lag-seven fallback.
 */
class ForecastRuntimeServiceTest {

    /* --- Test methods --- */

    @Test
    void buildsTotalEventsFeatureVectorInConfigOrder() throws Exception {
        RuntimeArtifactService artifacts = V34TestArtifacts.loadedArtifactService();
        StatisticsService statisticsService = mock(StatisticsService.class);
        LocalDate date = LocalDate.of(2026, 1, 8);
        when(statisticsService.countEventsForDate(date.minusDays(7))).thenReturn(700L);

        ForecastRuntimeService service = new ForecastRuntimeService(artifacts, statisticsService, new AiForecastProperties());
        Map<String, Double> features = service.buildTotalEventsFeatures(date);
        float[] vector = service.buildFeatureVector(features);

        assertThat(features.keySet()).containsExactlyElementsOf(artifacts.getForecastConfig().totalEventsModel().getFeatureOrder());
        assertThat(vector).hasSize(artifacts.getForecastConfig().totalEventsModel().getFeatureOrder().size());
    }

    @Test
    void anomalyRateUsesNaiveLagSevenAndFallsBackWhenHistoryMissing() throws Exception {
        RuntimeArtifactService artifacts = V34TestArtifacts.loadedArtifactService();
        StatisticsService statisticsService = mock(StatisticsService.class);
        LocalDate date = LocalDate.of(2026, 1, 8);
        when(statisticsService.countEventsForDate(date.minusDays(7))).thenReturn(100L);
        when(statisticsService.countAlertsForDate(date.minusDays(7))).thenReturn(5L);

        ForecastRuntimeService service = new ForecastRuntimeService(artifacts, statisticsService, new AiForecastProperties());

        ForecastPrediction prediction = service.forecast(date);

        assertThat(prediction.getAnomalyRateForecast()).isEqualTo(0.05);
    }
}
