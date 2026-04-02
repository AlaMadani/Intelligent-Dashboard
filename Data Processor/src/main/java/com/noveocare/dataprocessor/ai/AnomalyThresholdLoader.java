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
 * Loads the anomaly-score threshold produced during model calibration.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AnomalyThresholdLoader {

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    @Getter
    private AnomalyThreshold anomalyThreshold;

    @PostConstruct
    public void load() throws IOException {
        // Keep the threshold in memory because it is checked for many sessions.
        String path = properties.getBasePath() + properties.getFiles().getAnomalyThreshold();
        Resource resource = resourceLoader.getResource(path);
        try (InputStream inputStream = resource.getInputStream()) {
            anomalyThreshold = objectMapper.readValue(inputStream, AnomalyThreshold.class);
        }
        log.info("Loaded anomaly_threshold.json (threshold={})", anomalyThreshold.getThreshold());
    }
}
