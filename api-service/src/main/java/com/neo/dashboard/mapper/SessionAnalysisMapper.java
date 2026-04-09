package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.SessionAnalysisDto;
import com.neo.dashboard.entity.SessionAnalysis;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps session analysis entities to DTOs and parses JSON-backed columns.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface SessionAnalysisMapper extends EntityMapper<SessionAnalysisDto, SessionAnalysis> {

    ObjectMapper JSON = new ObjectMapper();

    @Override
    @Mapping(target = "actionCounts", source = "actionCountsJson", qualifiedByName = "parseActionCounts")
    @Mapping(target = "top3NextActions", source = "top3NextActions", qualifiedByName = "parseTop3Actions")
    SessionAnalysisDto toDto(SessionAnalysis entity);

    @Named("parseActionCounts")
    default Map<String, Long> parseActionCounts(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return JSON.readValue(json, new TypeReference<Map<String, Long>>() {
            });
        } catch (Exception first) {
            try {
                Map<String, Number> raw = JSON.readValue(json, new TypeReference<Map<String, Number>>() {
                });
                if (raw == null || raw.isEmpty()) {
                    return Collections.emptyMap();
                }
                return raw.entrySet().stream()
                        .filter(entry -> entry.getValue() != null)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().longValue()
                        ));
            } catch (Exception ignored) {
                return Collections.emptyMap();
            }
        }
    }

    @Named("parseTop3Actions")
    default List<String> parseTop3Actions(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }

        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }
}

