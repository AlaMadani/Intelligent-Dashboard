package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36AnomalyTypeAttributionDto {
    private String anomalyType;
    @JsonAlias("anomalyTypeConfidence")
    private Double confidence;
    private String source;
    private Map<String, Object> evidence;
}
