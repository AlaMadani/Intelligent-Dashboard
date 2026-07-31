package com.noveocare.dataprocessor.ai.sequence;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Result of a sequence ONNX inference: model kind, categorical logits,
 * continuous prediction vector, latency, and output metadata.
 */
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
