package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SequenceWindowService {

    private final RuntimeArtifactService artifactService;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;

    public SequenceWindowState load(String sessionId) {
        SequenceWindowState state = redisCacheService.getJson(CacheKeys.sequenceWindowKey(sessionId), SequenceWindowState.class);
        if (state == null || state.getEvents() == null) {
            return SequenceWindowState.builder().events(new ArrayList<>()).build();
        }
        state.getEvents().sort(Comparator.comparing(EncodedSequenceEvent::getTimestamp, Comparator.nullsLast(Instant::compareTo)));
        return state;
    }

    public void appendAndSave(String sessionId, EncodedSequenceEvent encodedEvent) {
        SequenceWindowState state = load(sessionId);
        List<EncodedSequenceEvent> events = new ArrayList<>(state.getEvents());
        events.add(encodedEvent);
        events.sort(Comparator.comparing(EncodedSequenceEvent::getTimestamp, Comparator.nullsLast(Instant::compareTo)));
        int windowSize = artifactService.getSequenceMetadata().getWindowSize();
        if (events.size() > windowSize) {
            events = new ArrayList<>(events.subList(events.size() - windowSize, events.size()));
        }
        redisCacheService.setJson(
                CacheKeys.sequenceWindowKey(sessionId),
                SequenceWindowState.builder().events(events).build(),
                cacheProperties.getSessionBuffer());
    }

    public SequenceWindow toWindow(SequenceWindowState state) {
        int windowSize = artifactService.getSequenceMetadata().getWindowSize();
        int catCount = artifactService.getSequenceMetadata().getCatCols().size();
        int contCount = artifactService.getSequenceMetadata().getContCols().size();
        long[][][] xCat = new long[1][windowSize][catCount];
        float[][][] xCont = new float[1][windowSize][contCount];
        boolean[][] mask = new boolean[1][windowSize];

        List<EncodedSequenceEvent> events = state == null || state.getEvents() == null
                ? List.of()
                : state.getEvents().stream()
                .sorted(Comparator.comparing(EncodedSequenceEvent::getTimestamp, Comparator.nullsLast(Instant::compareTo)))
                .toList();
        int count = Math.min(events.size(), windowSize);
        int sourceStart = Math.max(0, events.size() - count);
        int targetStart = windowSize - count;
        for (int i = 0; i < count; i++) {
            EncodedSequenceEvent event = events.get(sourceStart + i);
            int target = targetStart + i;
            for (int c = 0; c < catCount; c++) {
                xCat[0][target][c] = event.getCategoricalIds() == null || c >= event.getCategoricalIds().length
                        ? 0L
                        : event.getCategoricalIds()[c];
            }
            for (int c = 0; c < contCount; c++) {
                xCont[0][target][c] = event.getContinuousValues() == null || c >= event.getContinuousValues().length
                        ? 0.0f
                        : event.getContinuousValues()[c];
            }
            mask[0][target] = true;
        }
        return SequenceWindow.builder()
                .xCat(xCat)
                .xCont(xCont)
                .mask(mask)
                .realEventCount(count)
                .build();
    }

    public Instant latestTimestamp(SequenceWindowState state) {
        EncodedSequenceEvent last = state == null ? null : state.lastEvent();
        return last == null ? null : last.getTimestamp();
    }
}
