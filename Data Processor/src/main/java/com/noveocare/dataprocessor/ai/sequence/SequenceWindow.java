package com.noveocare.dataprocessor.ai.sequence;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SequenceWindow {
    long[][][] xCat;
    float[][][] xCont;
    boolean[][] mask;
    int realEventCount;
}
