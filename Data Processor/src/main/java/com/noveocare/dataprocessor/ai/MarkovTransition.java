package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarkovTransition {
    @JsonProperty("to_action")
    private String toAction;

    private Double probability;
}
