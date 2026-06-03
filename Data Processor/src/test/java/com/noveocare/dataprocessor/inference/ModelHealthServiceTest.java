package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactHealth;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.churn.ChurnInferenceService;
import com.noveocare.dataprocessor.ai.forecast.ForecastRuntimeService;
import com.noveocare.dataprocessor.ai.sequence.SequenceFieldCoverageMonitor;
import com.noveocare.dataprocessor.ai.sequence.SequenceOnnxInferenceService;
import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyInferenceService;
import com.noveocare.dataprocessor.ai.tabular.TabularFieldCoverageMonitor;
import com.noveocare.dataprocessor.config.AiChurnProperties;
import com.noveocare.dataprocessor.config.AiForecastProperties;
import com.noveocare.dataprocessor.config.AiLlmExplanationProperties;
import com.noveocare.dataprocessor.config.AiPersonaProperties;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.AiTabularAnomalyProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ModelHealthServiceTest {

    @Test
    void snapshotDistinguishesArtifactPresenceFromRuntimeInitializationAndInferenceState() {
        SequenceOnnxInferenceService onnx = mock(SequenceOnnxInferenceService.class);
        TabularAnomalyInferenceService tabular = mock(TabularAnomalyInferenceService.class);
        ChurnInferenceService churn = mock(ChurnInferenceService.class);
        ForecastRuntimeService forecast = mock(ForecastRuntimeService.class);
        RuntimeArtifactService artifacts = mock(RuntimeArtifactService.class);
        SequenceFieldCoverageMonitor sequenceCoverage = mock(SequenceFieldCoverageMonitor.class);
        TabularFieldCoverageMonitor tabularCoverage = mock(TabularFieldCoverageMonitor.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        RedisCacheProperties ttl = new RedisCacheProperties();
        ttl.setLiveStats(Duration.ofMinutes(5));

        when(artifacts.getArtifactHealth()).thenReturn(RuntimeArtifactHealth.builder()
                .runtimeVersion("v3.6.1")
                .artifactBasePath("classpath:/AI/")
                .personaSkippedReason("Persona intentionally skipped in V3.6.1 dataprocessor refactor")
                .missingArtifacts(List.of())
                .warnings(List.of())
                .build());
        when(artifacts.modelExists(RuntimeArtifactService.TRANSFORMER_MODEL)).thenReturn(true);
        when(artifacts.modelExists(RuntimeArtifactService.TCN_MODEL)).thenReturn(true);
        when(artifacts.modelExists(RuntimeArtifactService.TABULAR_XGBOOST_JSON)).thenReturn(true);
        when(artifacts.modelExists(RuntimeArtifactService.TABULAR_LIGHTGBM)).thenReturn(true);
        when(artifacts.modelExists(RuntimeArtifactService.CHURN_MODEL)).thenReturn(true);
        when(artifacts.modelExists(RuntimeArtifactService.FORECAST_ANOMALY_RATE_RIDGE)).thenReturn(true);
        when(artifacts.modelExists(RuntimeArtifactService.FORECAST_TOTAL_EVENTS_XGBOOST_JSON)).thenReturn(true);
        when(artifacts.modelExists(RuntimeArtifactService.PERSONA_MODEL)).thenReturn(true);
        when(artifacts.resourceExists("config/llm_explanation_config.json")).thenReturn(true);
        when(onnx.transformerLoaded()).thenReturn(false);
        when(onnx.tcnLoaded()).thenReturn(true);
        when(tabular.xgboostAvailable()).thenReturn(true);
        when(tabular.lightgbmAvailable()).thenReturn(false);
        when(churn.isAvailable()).thenReturn(true);
        when(forecast.ridgeLoaded()).thenReturn(true);
        when(forecast.xgboostLoaded()).thenReturn(false);
        when(sequenceCoverage.snapshot()).thenReturn(Map.of());
        when(sequenceCoverage.highUnknownWarnings()).thenReturn(List.of());
        when(tabularCoverage.snapshot()).thenReturn(Map.of());

        ModelHealthService service = new ModelHealthService(
                onnx,
                tabular,
                churn,
                forecast,
                artifacts,
                sequenceCoverage,
                tabularCoverage,
                redis,
                ttl,
                new AiSequenceProperties(),
                new AiTabularAnomalyProperties(),
                new AiChurnProperties(),
                new AiForecastProperties(),
                new AiPersonaProperties(),
                new AiLlmExplanationProperties());
        service.recordRuntimeSuccess("xgboost");
        service.recordRuntimeError("lightgbm", "lightgbm_alert_no_trees_parsed");

        Map<String, Object> snapshot = service.snapshot();

        assertThat(snapshot).containsEntry("schemaVersion", "v3.6.1");
        @SuppressWarnings("unchecked")
        Map<String, Object> modelHealth = (Map<String, Object>) snapshot.get("modelHealth");
        @SuppressWarnings("unchecked")
        Map<String, Object> transformer = (Map<String, Object>) modelHealth.get("transformerOnnx");
        assertThat(transformer).containsEntry("artifactExists", true);
        assertThat(transformer).containsEntry("runtimeInitialized", false);
        assertThat(transformer).containsEntry("unavailableReason", "runtime_unavailable_or_parse_failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> xgboost = (Map<String, Object>) modelHealth.get("xgboostAnomalyRanking");
        assertThat(xgboost).containsEntry("lastInferenceSucceeded", true);
        assertThat(xgboost.get("lastInferenceTimestamp")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> lightgbm = (Map<String, Object>) modelHealth.get("lightgbmAlerting");
        assertThat(lightgbm).containsEntry("lastInferenceSucceeded", false);
        assertThat(lightgbm).containsEntry("lastInferenceError", "lightgbm_alert_no_trees_parsed");
        @SuppressWarnings("unchecked")
        Map<String, Object> persona = (Map<String, Object>) modelHealth.get("persona");
        assertThat(persona).containsEntry("runtimeInitialized", false);
        assertThat(persona).containsEntry("inferenceEnabledByConfig", false);
    }

    @Test
    void publishWritesVersionedHealthToRedisKey() {
        SequenceOnnxInferenceService onnx = mock(SequenceOnnxInferenceService.class);
        TabularAnomalyInferenceService tabular = mock(TabularAnomalyInferenceService.class);
        ChurnInferenceService churn = mock(ChurnInferenceService.class);
        ForecastRuntimeService forecast = mock(ForecastRuntimeService.class);
        RuntimeArtifactService artifacts = mock(RuntimeArtifactService.class);
        SequenceFieldCoverageMonitor sequenceCoverage = mock(SequenceFieldCoverageMonitor.class);
        TabularFieldCoverageMonitor tabularCoverage = mock(TabularFieldCoverageMonitor.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        RedisCacheProperties ttl = new RedisCacheProperties();
        ttl.setLiveStats(Duration.ofMinutes(5));
        when(artifacts.getArtifactHealth()).thenReturn(RuntimeArtifactHealth.builder()
                .runtimeVersion("v3.6.1")
                .artifactBasePath("classpath:/AI/")
                .missingArtifacts(List.of())
                .warnings(List.of())
                .build());
        when(sequenceCoverage.snapshot()).thenReturn(Map.of());
        when(sequenceCoverage.highUnknownWarnings()).thenReturn(List.of());
        when(tabularCoverage.snapshot()).thenReturn(Map.of());

        ModelHealthService service = new ModelHealthService(
                onnx,
                tabular,
                churn,
                forecast,
                artifacts,
                sequenceCoverage,
                tabularCoverage,
                redis,
                ttl,
                new AiSequenceProperties(),
                new AiTabularAnomalyProperties(),
                new AiChurnProperties(),
                new AiForecastProperties(),
                new AiPersonaProperties(),
                new AiLlmExplanationProperties());

        service.publish();

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(redis).setJson(eq(CacheKeys.aiRuntimeHealthKey()), payloadCaptor.capture(), eq(Duration.ofMinutes(5)));
        assertThat(payloadCaptor.getValue()).containsEntry("schemaVersion", "v3.6.1");
    }
}
