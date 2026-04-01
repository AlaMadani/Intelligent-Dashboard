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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class VocabService {

    private final AiResourceProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    private Map<String, Integer> actionToId;
    private Map<String, Integer> deviceToId;
    private Map<String, Integer> countryToId;
    private Map<String, Integer> typeToId;
    private Map<String, Integer> subtypeToId;

    @Getter
    private Map<Integer, String> actionIdToLabel;

    @PostConstruct
    public void load() throws IOException {
        actionIdToLabel = loadIdToLabel(properties.getFiles().getActionVocab());
        actionToId = invert(actionIdToLabel);

        deviceToId = invert(loadIdToLabel(properties.getFiles().getDeviceVocab()));
        countryToId = invert(loadIdToLabel(properties.getFiles().getCountryVocab()));
        typeToId = invert(loadIdToLabel(properties.getFiles().getTypeVocab()));
        subtypeToId = invert(loadIdToLabel(properties.getFiles().getSubtypeVocab()));

        log.info("Loaded vocabularies (actions={}, devices={}, countries={}, types={}, subtypes={})",
                actionToId.size(), deviceToId.size(), countryToId.size(), typeToId.size(), subtypeToId.size());
    }

    public int actionId(String action) {
        return lookup(actionToId, action);
    }

    public int deviceId(String device) {
        return lookup(deviceToId, device);
    }

    public int countryId(String country) {
        return lookup(countryToId, country);
    }

    public int typeId(String type) {
        return lookup(typeToId, type);
    }

    public int subtypeId(String subtype) {
        if (subtype == null || subtype.isBlank()) {
            return 0;
        }
        return lookup(subtypeToId, subtype);
    }

    public String actionLabel(int actionId) {
        return actionIdToLabel.getOrDefault(actionId, "UNKNOWN");
    }

    private Map<Integer, String> loadIdToLabel(String fileName) throws IOException {
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

    private Map<String, Integer> invert(Map<Integer, String> idToLabel) {
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<Integer, String> entry : idToLabel.entrySet()) {
            result.put(normalize(entry.getValue()), entry.getKey());
        }
        return result;
    }

    private int lookup(Map<String, Integer> map, String value) {
        if (value == null) {
            return 0;
        }
        String normalized = normalize(value);
        Integer id = map.get(normalized);
        if (id != null) {
            return id;
        }
        String mojibake = toMojibake(normalized);
        id = map.get(mojibake);
        return id == null ? 0 : id;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private String toMojibake(String value) {
        if (value == null) {
            return null;
        }
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }
}
