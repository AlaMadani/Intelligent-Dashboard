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
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class LabelMapService {

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    @Getter
    private Map<Integer, String> anomalyTypeLabels;

    @Getter
    private Map<Integer, String> nextActionLabels;

    @PostConstruct
    public void load() throws IOException {
        anomalyTypeLabels = loadLabelMap(properties.getFiles().getAnomalyTypeLabelMap());
        nextActionLabels = loadLabelMap(properties.getFiles().getNextActionLabelMap());
        log.info("Loaded label maps (anomalyTypes={}, nextActions={})", anomalyTypeLabels.size(), nextActionLabels.size());
    }

    public String anomalyTypeLabel(int id) {
        return anomalyTypeLabels.getOrDefault(id, "UNKNOWN");
    }

    public String nextActionLabel(int id) {
        return nextActionLabels.getOrDefault(id, "UNKNOWN");
    }

    private Map<Integer, String> loadLabelMap(String fileName) throws IOException {
        String path = properties.getBasePath() + fileName;
        Resource resource = resourceLoader.getResource(path);
        try (InputStream inputStream = resource.getInputStream()) {
            Map<String, String> raw = objectMapper.readValue(inputStream, new TypeReference<Map<String, String>>() {});
            Map<Integer, String> result = new HashMap<>();
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                result.put(Integer.parseInt(entry.getKey()), entry.getValue());
            }
            return result;
        }
    }
}
