package com.noveocare.dataprocessor.ai.sequence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the list of encoded events for a session's sliding window, persisted
 * in Redis between requests.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SequenceWindowState {
    @Builder.Default
    private List<EncodedSequenceEvent> events = new ArrayList<>();

    /* Returns the number of real (non-padded) events in the window. */
    public int realEventCount() {
        return events == null ? 0 : events.size();
    }

    /* Returns the most recent event, or null if empty. */
    public EncodedSequenceEvent lastEvent() {
        if (events == null || events.isEmpty()) {
            return null;
        }
        return events.get(events.size() - 1);
    }
}
