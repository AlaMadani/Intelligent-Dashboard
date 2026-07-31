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

/**
 * DTO holding the next-event prediction output for a given session.
 * <p>
 * Contains the predicted next action/event heads (with probabilities),
 * a deviation analysis comparing the prediction against what actually
 * happened, model metadata, and warnings.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36NextEventPredictionDto {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** The insured person identifier this prediction relates to. */
    private String insuredId;
    /** Session identifier for which the prediction was made. */
    private String sessionId;
    /** Event identifier that served as the context for this prediction. */
    private String contextEventId;
    /** Number of context events used to generate the prediction. */
    private Integer contextSize;
    /** Name/identifier of the prediction model used. */
    private String model;
    /** Map of event-action categories to ranked prediction heads. */
    private Map<String, List<V36NextEventPredictionHeadItemDto>> heads;
    /** Deviation analysis comparing the prediction to actual events. */
    private V36NextEventPredictionDeviationDto deviation;
    /** Instant when this prediction was created. */
    private Instant createdAt;
    /** Source system or component that produced this prediction. */
    private String source;
    /** Warning messages from the prediction pipeline. */
    private List<String> warnings;

    /**
     * Factory method returning a minimal DTO with a warning to indicate
     * that the next-event prediction is unavailable.
     *
     * @param warning the reason the prediction is unavailable
     * @return a minimally populated {@code V36NextEventPredictionDto}
     */
    public static V36NextEventPredictionDto unavailable(String warning) {
        /* Build a response with only the warning set */
        V36NextEventPredictionDto dto = new V36NextEventPredictionDto();
        dto.setWarnings(List.of(warning));
        return dto;
    }

    /**
     * Appends a warning message to the {@link #warnings} list, creating
     * the list if it does not already exist.
     *
     * @param warning the warning text to add
     */
    public void addWarning(String warning) {
        /* Lazily initialize the warnings list */
        if (warnings == null) {
            warnings = new java.util.ArrayList<>();
        }
        warnings.add(warning);
    }

    /**
     * Represents a single predicted "head" (next action/value) with its
     * probability and rank among all candidate heads.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class V36NextEventPredictionHeadItemDto {
        /** The predicted value (e.g. event action name). */
        private String value;
        /** Probability assigned to this prediction (0.0 – 1.0). */
        private Double probability;
        /** Rank of this prediction among all candidates (1-based). */
        private Integer rank;
    }

    /**
     * Represents the deviation between a predicted next event and the
     * actual event that occurred, including per-field match status and
     * an overall deviation score.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class V36NextEventPredictionDeviationDto {
        /** The actual event data that occurred. */
        private Map<String, Object> actual;
        /** Per-field match indicator (true = matched prediction). */
        private Map<String, Boolean> predictionMatch;
        /** Probabilities assigned to the actual values by the prediction model. */
        private Map<String, Double> actualProbabilities;
        /** Overall deviation score between prediction and reality. */
        private Double deviationScore;
        /** The previous prediction data for comparison. */
        private Map<String, Object> previousPrediction;
        /** Context event identifier for the previous prediction. */
        private String previousPredictionContextEventId;
        /** Event identifier that was evaluated for deviation. */
        private String evaluatedEventId;
    }
}
