package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeArtifactServiceTest {

    @Test
    void loadsManifestFeatureBundleAndForecastArtifacts() throws Exception {
        AiResourceProperties properties = new AiResourceProperties();
        properties.setBasePath("classpath:/AI/");
        properties.setManifest("deployment_manifest.json");
        properties.setFeatureBundle("feature_bundle.json");

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        RuntimeArtifactService service = new RuntimeArtifactService(properties, new DefaultResourceLoader(), objectMapper);

        service.load();

        assertEquals("xgb_binary.onnx", service.getDeploymentManifest().getBinaryDetection().getPreferred());
        assertEquals("iso_binary.onnx", service.resolveBinaryArtifact());
        assertTrue(service.getBinaryFeatureColumns().contains("totalEvents"));
        assertTrue(service.getAnomalyTypeLabels().containsValue("geo_jump"));
        assertFalse(service.getMarkovLookup().isEmpty());
        assertTrue(service.getForecastSeries().containsKey("total_events"));
        assertFalse(service.getDashboardExports().get("top_risky_sessions.csv").isEmpty());
        assertFalse(service.getDashboardExports().get("path_deviations.csv").isEmpty());
        assertTrue(service.getBinaryFeatureColumns().stream().anyMatch(column -> column.contains("Déconnexion")));
    }
}
