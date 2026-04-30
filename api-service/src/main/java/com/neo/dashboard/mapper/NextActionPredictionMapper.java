package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.dto.NextActionScoreDto;
import com.neo.dashboard.entity.NextActionPrediction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps next-action prediction entities to DTOs and parses JSON arrays.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface NextActionPredictionMapper extends EntityMapper<NextActionPredictionDto, NextActionPrediction> {

    ObjectMapper JSON = new ObjectMapper();

    @Override
    @Mapping(target = "top3Actions", source = "top3ActionsJson", qualifiedByName = "parseActions")
    NextActionPredictionDto toDto(NextActionPrediction entity);

    @Named("parseActions")
    default List<NextActionScoreDto> parseActions(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<NextActionScoreDto>>() {
            });
        } catch (Exception e) {
            try {
                List<String> labels = JSON.readValue(json, new TypeReference<List<String>>() { });
                return labels.stream()
                        .map(l -> new NextActionScoreDto(l, null))
                        .collect(Collectors.toList());
            } catch (Exception ignored) {
                return Collections.emptyList();
            }
        }
    }
}

