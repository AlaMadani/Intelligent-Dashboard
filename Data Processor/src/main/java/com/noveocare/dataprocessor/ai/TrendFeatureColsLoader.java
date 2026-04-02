package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.List;

/**
 * Loads the ordered trend-feature column list used by the XGBoost model.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TrendFeatureColsLoader {

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    @Getter
    private List<String> featureColumns;

    @PostConstruct
    public void load() throws IOException {
        // Preserve file order because the trained model expects features in this exact sequence.
        String path = properties.getBasePath() + properties.getFiles().getTrendFeatureCols();
        Resource resource = resourceLoader.getResource(path);
        try (InputStream inputStream = resource.getInputStream()) {
            featureColumns = objectMapper.readValue(inputStream, new TypeReference<List<String>>() {});
        }
        log.info("Loaded trend_feature_cols.json ({} columns)", featureColumns.size());
    }
}
