package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.entity.NextActionPrediction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * Maps next-action prediction entities to DTOs and parses JSON arrays.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = JsonParsingSupport.class)
public interface NextActionPredictionMapper extends EntityMapper<NextActionPredictionDto, NextActionPrediction> {

    @Override
    @Mapping(target = "top3Actions", source = "top3ActionsJson", qualifiedByName = "parseTop3Actions")
    NextActionPredictionDto toDto(NextActionPrediction entity);
}