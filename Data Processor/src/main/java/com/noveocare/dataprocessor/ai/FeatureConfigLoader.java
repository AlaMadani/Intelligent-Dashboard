package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;

@Component
@Slf4j
@RequiredArgsConstructor
public class FeatureConfigLoader {

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    @Getter
    private FeatureConfig featureConfig;

    @PostConstruct
    public void load() throws IOException {
        String path = properties.getBasePath() + properties.getFiles().getFeatureConfig();
        Resource resource = resourceLoader.getResource(path);
        try (InputStream inputStream = resource.getInputStream()) {
            featureConfig = objectMapper.readValue(inputStream, FeatureConfig.class);
        }
        log.info("Loaded feature_config.json (seq_len={}, n_features={})", featureConfig.getSeqLen(), featureConfig.getNFeatures());
    }
}
