package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ModelRuntimeStateDto {
    private Boolean artifactExists;
    private Boolean artifactParsed;
    private Boolean runtimeInitialized;
    private Boolean inferenceEnabledByConfig;
    private Boolean lastInferenceSucceeded;
    private String lastInferenceError;
    private Instant lastInferenceTimestamp;
    private String unavailableReason;
}
