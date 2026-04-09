package com.noveocare.dataprocessor.mapper;

import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface AnomalyAlertMapper extends GenericMapper<AnomalyAlert, AnomalyEvent> {

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "eventJson", ignore = true)
    @Mapping(target = "detectedAt", source = "detectedAt", qualifiedByName = "detectedAtOrNow")
    AnomalyEvent toEntity(AnomalyAlert dto);

    @Override
    void updateEntity(AnomalyAlert dto, @MappingTarget AnomalyEvent entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "eventJson", source = "rawEventJson")
    @Mapping(target = "detectedAt", source = "alert.detectedAt", qualifiedByName = "detectedAtOrNow")
    AnomalyEvent toEntity(AnomalyAlert alert, String rawEventJson);

    @Named("detectedAtOrNow")
    default Instant detectedAtOrNow(Instant detectedAt) {
        return detectedAt != null ? detectedAt : Instant.now();
    }
}
