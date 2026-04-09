package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.UserRiskProfileDto;
import com.neo.dashboard.entity.UserRiskProfile;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * Maps risk profile entities to API DTOs.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserRiskProfileMapper extends EntityMapper<UserRiskProfileDto, UserRiskProfile> {
}

