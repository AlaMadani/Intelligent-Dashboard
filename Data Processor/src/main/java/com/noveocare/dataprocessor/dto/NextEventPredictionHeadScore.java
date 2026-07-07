package com.noveocare.dataprocessor.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Value;

import java.util.List;

@Value
@JsonDeserialize(builder = NextEventPredictionHeadScore.HeadScoreBuilder.class)
public class NextEventPredictionHeadScore {
    String headName;
    List<PredictedValue> topK;

    @Value
    @JsonDeserialize(builder = PredictedValue.PredictedValueBuilder.class)
    public static class PredictedValue {
        String value;
        double probability;
        int rank;

        @JsonPOJOBuilder(withPrefix = "")
        public static class PredictedValueBuilder {
            private String value;
            private double probability;
            private int rank;

            public PredictedValueBuilder value(String value) { this.value = value; return this; }
            public PredictedValueBuilder probability(double probability) { this.probability = probability; return this; }
            public PredictedValueBuilder rank(int rank) { this.rank = rank; return this; }
            public PredictedValue build() {
                return new PredictedValue(value, probability, rank);
            }
        }
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class HeadScoreBuilder {
        private String headName;
        private List<PredictedValue> topK;

        public HeadScoreBuilder headName(String headName) { this.headName = headName; return this; }
        public HeadScoreBuilder topK(List<PredictedValue> topK) { this.topK = topK; return this; }
        public NextEventPredictionHeadScore build() {
            return new NextEventPredictionHeadScore(headName, topK);
        }
    }
}
