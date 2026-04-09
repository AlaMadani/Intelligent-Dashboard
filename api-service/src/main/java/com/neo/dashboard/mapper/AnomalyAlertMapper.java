package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.entity.AnomalyEvent;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * Maps persisted anomaly events to alert DTOs.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface AnomalyAlertMapper extends EntityMapper<AnomalyAlertDto, AnomalyEvent> {
}

