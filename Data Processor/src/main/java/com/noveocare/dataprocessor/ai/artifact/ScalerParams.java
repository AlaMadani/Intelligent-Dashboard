package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScalerParams {
    private String type;
    @JsonProperty("scaled_columns")
    private List<String> scaledColumns = List.of();
    private List<Double> center = List.of();
    private List<Double> scale = List.of();
    @JsonProperty("clip_bounds")
    private ClipBounds clipBounds = new ClipBounds();
    @JsonProperty("unscaled_context_columns")
    private List<String> unscaledContextColumns = List.of();
    @JsonProperty("formula_notes")
    private Map<String, String> formulaNotes = Map.of();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClipBounds {
        private boolean available;
        @JsonProperty("clip_low")
        private Double clipLow;
        @JsonProperty("clip_high")
        private Double clipHigh;
        private String source;
    }
}
