package com.noveocare.dataprocessor.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public abstract class AnomalyAlertMapper implements GenericMapper<AnomalyAlert, AnomalyEvent> {

    @Autowired
    protected ObjectMapper objectMapper;

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "eventJson", ignore = true)
    @Mapping(target = "nextActionsJson", source = "nextActions", qualifiedByName = "stringifyList")
    @Mapping(target = "detectedAt", source = "detectedAt", qualifiedByName = "detectedAtOrNow")
    public abstract AnomalyEvent toEntity(AnomalyAlert dto);

    @Override
    public abstract void updateEntity(AnomalyAlert dto, @MappingTarget AnomalyEvent entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "eventJson", source = "rawEventJson")
    @Mapping(target = "nextActionsJson", source = "alert.nextActions", qualifiedByName = "stringifyList")
    @Mapping(target = "detectedAt", source = "alert.detectedAt", qualifiedByName = "detectedAtOrNow")
    public abstract AnomalyEvent toEntity(AnomalyAlert alert, String rawEventJson);

    @Named("detectedAtOrNow")
    protected Instant detectedAtOrNow(Instant detectedAt) {
        return detectedAt != null ? detectedAt : Instant.now();
    }

    @Named("stringifyList")
    protected String stringifyList(List<?> values) {
        if (values == null) return null;
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return values.toString();
        }
    }
}
