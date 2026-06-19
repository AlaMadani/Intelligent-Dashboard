package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.AiPersonaProperties;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeArtifactServiceTest {

    @Test
    void loadsV36ArtifactsAndValidatesSequenceContract() throws Exception {
        RuntimeArtifactService service = artifactService();

        service.load();

        assertThat(service.getManifest().getPackageType()).isEqualTo("springboot_dataprocessor_runtime_artifacts_v3_6_3_self_contained_onnx");
        assertThat(service.getSequenceMetadata().getCatCols()).hasSize(15);
        assertThat(service.getSequenceMetadata().getContCols()).hasSize(9);
        assertThat(service.getSequenceMetadata().getWindowSize()).isEqualTo(10);
        assertThat(service.getCategoricalVocabularies().getInputIdMaps1Based())
                .containsKeys("page", "api_template", "environment_id");
        assertThat(service.modelExists(RuntimeArtifactService.TRANSFORMER_MODEL)).isTrue();
        assertThat(service.modelExists(RuntimeArtifactService.TCN_MODEL)).isTrue();
        assertThat(service.modelExists(RuntimeArtifactService.CHURN_MODEL)).isTrue();
        assertThat(service.modelExists(RuntimeArtifactService.FORECAST_TOTAL_EVENTS_XGBOOST_JSON)).isTrue();
        assertThat(service.getArtifactHealth().isPersonaEnabled()).isFalse();
        assertThat(service.getArtifactHealth().isLlmExplanationInDataprocessor()).isFalse();
        assertThat(service.getArtifactHealth().isLlmEvidencePayloadEnabled()).isTrue();
    }

    static RuntimeArtifactService artifactService() {
        AiResourceProperties properties = new AiResourceProperties();
        properties.setBasePath("classpath:/AI/");
        properties.setModelsPath("models/");
        properties.setConfigPath("config/");
        properties.setReportsPath("reports/");
        return new RuntimeArtifactService(properties, new AiPersonaProperties(), new DefaultResourceLoader(), new ObjectMapper().findAndRegisterModules());
    }
}
