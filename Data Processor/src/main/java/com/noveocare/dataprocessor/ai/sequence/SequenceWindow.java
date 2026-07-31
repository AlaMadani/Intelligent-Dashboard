package com.noveocare.dataprocessor.ai.sequence;

import lombok.Builder;
import lombok.Value;

/**
 * A window of encoded events formatted as ONNX input tensors: categorical
 * IDs, continuous values, and a mask indicating real vs padded positions.
 */
@Value
@Builder
public class SequenceWindow {
    long[][][] xCat;
    float[][][] xCont;
    boolean[][] mask;
    int realEventCount;
}
