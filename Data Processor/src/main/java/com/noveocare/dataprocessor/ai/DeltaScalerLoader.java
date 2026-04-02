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

/**
 * Loads the normalization statistics used for delta-time features.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeltaScalerLoader {

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    @Getter
    private DeltaScaler deltaScaler;

    @PostConstruct
    public void load() throws IOException {
        // Deserialize the scaler JSON once and share it across all feature builders.
        String path = properties.getBasePath() + properties.getFiles().getScalerDelta();
        Resource resource = resourceLoader.getResource(path);
        try (InputStream inputStream = resource.getInputStream()) {
            deltaScaler = objectMapper.readValue(inputStream, DeltaScaler.class);
        }
        log.info("Loaded scaler_delta.json (mean={}, scale={})", deltaScaler.meanValue(), deltaScaler.scaleValue());
    }
}
