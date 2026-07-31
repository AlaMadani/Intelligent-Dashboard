package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO representing the runtime health state of a single model.
 * <p>
 * Tracks the model artifact lifecycle (exists, parsed), whether the
 * runtime is initialized and inference is enabled, plus the outcome
 * of the last inference attempt.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36ModelRuntimeStateDto {
    /** Whether the model artifact file exists on disk. */
    private Boolean artifactExists;
    /** Whether the model artifact was successfully parsed/loaded. */
    private Boolean artifactParsed;
    /** Whether the model runtime has been initialized. */
    private Boolean runtimeInitialized;
    /** Whether inference is enabled by the current configuration. */
    private Boolean inferenceEnabledByConfig;
    /** Whether the last inference execution succeeded. */
    private Boolean lastInferenceSucceeded;
    /** Error message from the last failed inference (null if successful). */
    private String lastInferenceError;
    /** Timestamp of the last inference attempt. */
    private Instant lastInferenceTimestamp;
    /** Reason the model is unavailable (e.g. "artifact missing", "config disabled"). */
    private String unavailableReason;
}
