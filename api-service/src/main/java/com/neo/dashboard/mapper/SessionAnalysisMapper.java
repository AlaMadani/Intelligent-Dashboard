package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.SessionAnalysisDto;
import com.neo.dashboard.entity.SessionAnalysis;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * Maps session analysis entities to DTOs and parses JSON-backed columns.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = JsonParsingSupport.class)
public interface SessionAnalysisMapper extends EntityMapper<SessionAnalysisDto, SessionAnalysis> {

    @Override
    @Mapping(target = "actionCounts", source = "actionCountsJson", qualifiedByName = "parseActionCounts")
    @Mapping(target = "top3NextActions", source = "top3NextActions", qualifiedByName = "parseTop3Actions")
    @Mapping(target = "anomalyTypes", source = "anomalyTypesJson", qualifiedByName = "parseStringList")
    @Mapping(target = "campaignIds", source = "campaignIdsJson", qualifiedByName = "parseStringList")
    SessionAnalysisDto toDto(SessionAnalysis entity);
}
