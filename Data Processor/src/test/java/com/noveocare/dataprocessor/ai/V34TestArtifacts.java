package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiPersonaProperties;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Test artifacts for V3.4 AI resources – provides a pre-configured RuntimeArtifactService
 * loaded from classpath for use across multiple test classes.
 */
public final class V34TestArtifacts {

    /* --- Constructor --- */

    private V34TestArtifacts() {
    }

    /* --- Factory methods --- */

    public static RuntimeArtifactService loadedArtifactService() throws Exception {
        AiResourceProperties properties = new AiResourceProperties();
        properties.setBasePath("classpath:/AI/");
        properties.setModelsPath("models/");
        properties.setConfigPath("config/");
        properties.setReportsPath("reports/");
        RuntimeArtifactService service = new RuntimeArtifactService(
                properties,
                new AiPersonaProperties(),
                new DefaultResourceLoader(),
                new ObjectMapper().findAndRegisterModules());
        service.load();
        return service;
    }
}
