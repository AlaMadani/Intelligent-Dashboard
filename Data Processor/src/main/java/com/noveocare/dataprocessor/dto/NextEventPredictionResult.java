package com.noveocare.dataprocessor.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Complete prediction output from the next-event model for a given context.
 */
@Value
@JsonDeserialize(builder = NextEventPredictionResult.ResultBuilder.class)
public class NextEventPredictionResult {
    String schemaVersion;
    String insuredId;
    String sessionId;
    String contextEventId;
    int contextSize;
    String model;
    String modelArtifact;
    /* Predictions grouped by head name (flat map form). */
    Map<String, List<NextEventPredictionHeadScore.PredictedValue>> heads;
    /* Structured per-head top-K results. */
    List<NextEventPredictionHeadScore> headScores;
    Instant createdAt;

    /* Deviation information when the actual event is compared against the prediction. */
    Map<String, Object> deviation;

    @JsonPOJOBuilder(withPrefix = "")
    public static class ResultBuilder {
        /* -- all fields match the parent class -- */
        private String schemaVersion;
        private String insuredId;
        private String sessionId;
        private String contextEventId;
        private int contextSize;
        private String model;
        private String modelArtifact;
        private Map<String, List<NextEventPredictionHeadScore.PredictedValue>> heads;
        private List<NextEventPredictionHeadScore> headScores;
        private Instant createdAt;
        private Map<String, Object> deviation;

        public ResultBuilder schemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public ResultBuilder insuredId(String insuredId) { this.insuredId = insuredId; return this; }
        public ResultBuilder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public ResultBuilder contextEventId(String contextEventId) { this.contextEventId = contextEventId; return this; }
        public ResultBuilder contextSize(int contextSize) { this.contextSize = contextSize; return this; }
        public ResultBuilder model(String model) { this.model = model; return this; }
        public ResultBuilder modelArtifact(String modelArtifact) { this.modelArtifact = modelArtifact; return this; }
        public ResultBuilder heads(Map<String, List<NextEventPredictionHeadScore.PredictedValue>> heads) { this.heads = heads; return this; }
        public ResultBuilder headScores(List<NextEventPredictionHeadScore> headScores) { this.headScores = headScores; return this; }
        public ResultBuilder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public ResultBuilder deviation(Map<String, Object> deviation) { this.deviation = deviation; return this; }
        public NextEventPredictionResult build() {
            return new NextEventPredictionResult(schemaVersion, insuredId, sessionId, contextEventId, contextSize,
                    model, modelArtifact, heads, headScores, createdAt, deviation);
        }
    }
}
