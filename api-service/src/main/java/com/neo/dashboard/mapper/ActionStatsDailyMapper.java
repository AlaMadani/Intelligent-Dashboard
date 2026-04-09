package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.ActionStatsDailyDto;
import com.neo.dashboard.entity.ActionStatsDaily;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * Maps action aggregate entities to API DTOs.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ActionStatsDailyMapper extends EntityMapper<ActionStatsDailyDto, ActionStatsDaily> {
}

