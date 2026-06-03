package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiPersonaProperties;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import org.springframework.core.io.DefaultResourceLoader;

public final class V34TestArtifacts {

    private V34TestArtifacts() {
    }

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
