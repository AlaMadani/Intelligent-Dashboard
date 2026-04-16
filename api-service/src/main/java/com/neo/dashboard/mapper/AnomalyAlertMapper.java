package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.entity.AnomalyEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/**
 * Maps persisted anomaly events to alert DTOs (Redis / SSE payload shape).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = JsonParsingSupport.class)
public interface AnomalyAlertMapper extends EntityMapper<AnomalyAlertDto, AnomalyEvent> {

    @Override
    @Mapping(target = "nextActions", source = "nextActionsJson", qualifiedByName = "parseNextActionsJson")
    AnomalyAlertDto toDto(AnomalyEvent entity);
}
