package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36NextEventPredictionDto {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private String insuredId;
    private String sessionId;
    private String contextEventId;
    private Integer contextSize;
    private String model;
    private Map<String, List<V36NextEventPredictionHeadItemDto>> heads;
    private V36NextEventPredictionDeviationDto deviation;
    private Instant createdAt;
    private String source;
    private List<String> warnings;

    public static V36NextEventPredictionDto unavailable(String warning) {
        V36NextEventPredictionDto dto = new V36NextEventPredictionDto();
        dto.setWarnings(List.of(warning));
        return dto;
    }

    public void addWarning(String warning) {
        if (warnings == null) {
            warnings = new java.util.ArrayList<>();
        }
        warnings.add(warning);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class V36NextEventPredictionHeadItemDto {
        private String value;
        private Double probability;
        private Integer rank;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class V36NextEventPredictionDeviationDto {
        private Map<String, Object> actual;
        private Map<String, Boolean> predictionMatch;
        private Map<String, Double> actualProbabilities;
        private Double deviationScore;
        private Map<String, Object> previousPrediction;
        private String previousPredictionContextEventId;
        private String evaluatedEventId;
    }
}
