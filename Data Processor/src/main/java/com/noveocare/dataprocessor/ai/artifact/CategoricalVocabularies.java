package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CategoricalVocabularies {
    @JsonProperty("input_id_maps_1_based")
    private Map<String, Map<String, Integer>> inputIdMaps1Based = Map.of();
    @JsonProperty("reverse_input_id_maps")
    private Map<String, Map<String, String>> reverseInputIdMaps = Map.of();
}
