package com.noveocare.dataprocessor.ai.sequence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SequenceWindowState {
    @Builder.Default
    private List<EncodedSequenceEvent> events = new ArrayList<>();

    public int realEventCount() {
        return events == null ? 0 : events.size();
    }

    public EncodedSequenceEvent lastEvent() {
        if (events == null || events.isEmpty()) {
            return null;
        }
        return events.get(events.size() - 1);
    }
}
