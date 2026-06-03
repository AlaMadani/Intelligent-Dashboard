package com.noveocare.dataprocessor.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.dto.AnomalyAlert;
import com.noveocare.dataprocessor.entity.AnomalyEvent;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    @Mapping(target = "modelScoresJson", source = "modelScores", qualifiedByName = "stringifyObject")
    @Mapping(target = "anomalyTypeEvidenceJson", source = "anomalyTypeEvidence", qualifiedByName = "stringifyObject")
    @Mapping(target = "modelContributionsJson", source = "modelContributions", qualifiedByName = "stringifyObject")
    @Mapping(target = "triggeredRulesJson", source = "triggeredRules", qualifiedByName = "stringifyObject")
    @Mapping(target = "churnContextJson", source = "churn", qualifiedByName = "stringifyObject")
    @Mapping(target = "personaContextJson", source = "persona", qualifiedByName = "stringifyObject")
    @Mapping(target = "artifactNamesJson", source = "artifactNames", qualifiedByName = "stringifyObject")
    @Mapping(target = "detectedAt", source = "detectedAt", qualifiedByName = "detectedAtOrNow")
    public abstract AnomalyEvent toEntity(AnomalyAlert dto);

    @AfterMapping
    protected void afterToEntity(AnomalyAlert dto, @MappingTarget AnomalyEvent entity) {
        entity.setV36RuntimeVersion(dto.getSchemaVersion());
        if (dto.getModelScores() != null) {
            Map<String, Object> ms = dto.getModelScores();
            entity.setXgboostAnomalyScore(toDouble(ms.get("xgboostAnomalyScore")));
            entity.setXgboostAnomalyScore100(toDouble(ms.get("xgboostAnomalyScore100")));
            entity.setLightgbmAlertScore(toDouble(ms.get("lightgbmAlertScore")));
            entity.setLightgbmAlertScore100(toDouble(ms.get("lightgbmAlertScore100")));
            entity.setTransformerRiskScore100(toDouble(ms.get("transformerRiskScore100")));
            entity.setTcnRiskScore100(toDouble(ms.get("tcnRiskScore100")));
        }
        if (dto.getChurn() != null) {
            Object riskLevel = dto.getChurn().get("riskLevel");
            entity.setChurnRiskLevel(riskLevel == null ? null : riskLevel.toString());
        }
    }

    @Override
    public abstract void updateEntity(AnomalyAlert dto, @MappingTarget AnomalyEvent entity);

    @Mapping(target = "id", ignore = true)
@Mapping(target = "eventJson", source = "rawEventJson")
    @Mapping(target = "modelScoresJson", source = "alert.modelScores", qualifiedByName = "stringifyObject")
    @Mapping(target = "modelContributionsJson", source = "alert.modelContributions", qualifiedByName = "stringifyObject")
    @Mapping(target = "triggeredRulesJson", source = "alert.triggeredRules", qualifiedByName = "stringifyObject")
    @Mapping(target = "churnContextJson", source = "alert.churn", qualifiedByName = "stringifyObject")
    @Mapping(target = "personaContextJson", source = "alert.persona", qualifiedByName = "stringifyObject")
    @Mapping(target = "artifactNamesJson", source = "alert.artifactNames", qualifiedByName = "stringifyObject")
    @Mapping(target = "detectedAt", source = "alert.detectedAt", qualifiedByName = "detectedAtOrNow")
    public abstract AnomalyEvent toEntity(AnomalyAlert alert, String rawEventJson);

    @AfterMapping
    protected void afterToEntityWithEventJson(AnomalyAlert alert, @MappingTarget AnomalyEvent entity) {
        entity.setV36RuntimeVersion(alert.getSchemaVersion());
        if (alert.getModelScores() != null) {
            Map<String, Object> ms = alert.getModelScores();
            entity.setXgboostAnomalyScore(toDouble(ms.get("xgboostAnomalyScore")));
            entity.setXgboostAnomalyScore100(toDouble(ms.get("xgboostAnomalyScore100")));
            entity.setLightgbmAlertScore(toDouble(ms.get("lightgbmAlertScore")));
            entity.setLightgbmAlertScore100(toDouble(ms.get("lightgbmAlertScore100")));
            entity.setTransformerRiskScore100(toDouble(ms.get("transformerRiskScore100")));
            entity.setTcnRiskScore100(toDouble(ms.get("tcnRiskScore100")));
        }
        if (alert.getChurn() != null) {
            Object riskLevel = alert.getChurn().get("riskLevel");
            entity.setChurnRiskLevel(riskLevel == null ? null : riskLevel.toString());
        }
    }

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

    @Named("stringifyObject")
    protected String stringifyObject(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (NumberFormatException e) { return null; }
    }
}
