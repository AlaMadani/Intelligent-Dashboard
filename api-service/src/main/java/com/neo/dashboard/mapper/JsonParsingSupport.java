package com.neo.dashboard.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.neo.dashboard.dto.FeatureContributionDto;
import com.neo.dashboard.dto.NextActionScoreDto;
import com.neo.dashboard.dto.PathDeviationDto;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared JSON parsing helpers for MapStruct mappers.
 * Injected as a Spring bean so the shared ObjectMapper (with JavaTimeModule)
 * is reused instead of creating standalone instances.
 */
@Component
public class JsonParsingSupport {

    private final ObjectMapper objectMapper;

    public JsonParsingSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Named("parseActionCounts")
    public Map<String, Long> parseActionCounts(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Long>>() { });
        } catch (Exception first) {
            try {
                Map<String, Number> raw = objectMapper.readValue(json, new TypeReference<Map<String, Number>>() { });
                if (raw == null || raw.isEmpty()) {
                    return Collections.emptyMap();
                }
                return raw.entrySet().stream()
                        .filter(entry -> entry.getValue() != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().longValue()));
            } catch (Exception ignored) {
                return Collections.emptyMap();
            }
        }
    }

    @Named("parseStringList")
    public List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    @Named("parseTop3Actions")
    public List<NextActionScoreDto> parseTop3Actions(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<NextActionScoreDto>>() { });
        } catch (Exception e) {
            try {
                List<String> simple = objectMapper.readValue(json, new TypeReference<List<String>>() { });
                return simple.stream()
                        .map(a -> new NextActionScoreDto(a, null))
                        .collect(Collectors.toList());
            } catch (Exception ignored) {
                return Collections.emptyList();
            }
        }
    }

    /**
     * Handles both JSON arrays (of strings or objects) and the legacy {@code List#toString()} form persisted for next actions.
     */
    @Named("parseNextActionsJson")
    public List<NextActionScoreDto> parseNextActionsJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = json.trim();
        try {
            if (trimmed.startsWith("[")) {
                try {
                    return objectMapper.readValue(trimmed, new TypeReference<List<NextActionScoreDto>>() { });
                } catch (Exception e) {
                    List<String> strings = objectMapper.readValue(trimmed, new TypeReference<List<String>>() { });
                    return strings.stream()
                            .map(s -> new NextActionScoreDto(s, null))
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception ignored) {
            // fall through to bracket split
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) {
                return Collections.emptyList();
            }
            List<NextActionScoreDto> parts = new ArrayList<>();
            for (String piece : Arrays.asList(inner.split(","))) {
                String p = piece.trim();
                if (!p.isEmpty()) {
                    parts.add(new NextActionScoreDto(p, null));
                }
            }
            return parts;
        }
        return Collections.emptyList();
    }

    @Named("parseFeatureContributionList")
    public List<FeatureContributionDto> parseFeatureContributionList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<FeatureContributionDto>>() { });
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    @Named("parsePathDeviationList")
    public List<PathDeviationDto> parsePathDeviationList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PathDeviationDto>>() { });
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    @Named("parseJsonNode")
    public JsonNode parseJsonNode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Named("parseJsonObject")
    public Object parseJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}