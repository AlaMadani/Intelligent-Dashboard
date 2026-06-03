package com.noveocare.dataprocessor.ai.sequence;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class SequenceInferenceResult {
    SequenceModelKind modelKind;
    String modelArtifact;
    List<float[]> categoricalLogits;
    float[] continuousPrediction;
    long latencyMillis;
    Map<String, Object> outputMetadata;
}
