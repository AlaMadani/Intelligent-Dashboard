package com.neo.dashboard.mapper;

import com.neo.dashboard.dto.AnomalyAlertDto;
import com.neo.dashboard.dto.AnomalyEventDto;
import com.neo.dashboard.entity.AnomalyEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.time.Instant;

/**
 * Maps anomaly domain objects used by list/detail endpoints and streams.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface AnomalyEventMapper extends EntityMapper<AnomalyEventDto, AnomalyEvent> {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "eventJson", ignore = true)
    @Mapping(target = "detectedAt", expression = "java(resolveDetectedAt(alert.getDetectedAt()))")
    AnomalyEventDto fromAlert(AnomalyAlertDto alert);

    default Instant resolveDetectedAt(Instant detectedAt) {
        return detectedAt == null ? Instant.now() : detectedAt;
    }
}

