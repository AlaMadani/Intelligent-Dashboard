package com.noveocare.dataprocessor.ai.sequence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class EncodedSequenceEvent {
    private String insuredId;
    private String sessionId;
    private String eventId;
    private Instant timestamp;
    private long[] categoricalIds;
    private float[] continuousValues;
    private double[] rawContinuousValues;
    private Map<String, String> rawCategoricalValues;
    private List<String> warnings;
    private boolean schemaValid;

    public int targetIndex(int categoricalPosition) {
        if (categoricalIds == null || categoricalPosition < 0 || categoricalPosition >= categoricalIds.length) {
            return -1;
        }
        long encoded = categoricalIds[categoricalPosition];
        return encoded > 0 ? (int) encoded - 1 : -1;
    }
}
