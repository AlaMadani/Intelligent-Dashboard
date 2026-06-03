package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.V34TestArtifacts;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SequenceWindowServiceTest {

    private RuntimeArtifactService artifactService;
    private RedisCacheService redisCacheService;
    private SequenceWindowService windowService;

    @BeforeEach
    void setUp() throws Exception {
        artifactService = V34TestArtifacts.loadedArtifactService();
        redisCacheService = mock(RedisCacheService.class);
        RedisCacheProperties cacheProperties = new RedisCacheProperties();
        cacheProperties.setSessionBuffer(Duration.ofHours(2));
        windowService = new SequenceWindowService(artifactService, redisCacheService, cacheProperties);
    }

    @Test
    void padsPreviousWindowOnTheLeftAndBuildsMask() {
        SequenceWindowState state = SequenceWindowState.builder()
                .events(List.of(event(1, Instant.parse("2026-01-01T00:00:00Z")),
                        event(2, Instant.parse("2026-01-01T00:00:01Z"))))
                .build();

        SequenceWindow window = windowService.toWindow(state);

        assertThat(window.getRealEventCount()).isEqualTo(2);
        assertThat(window.getMask()[0][0]).isFalse();
        assertThat(window.getMask()[0][7]).isFalse();
        assertThat(window.getMask()[0][8]).isTrue();
        assertThat(window.getMask()[0][9]).isTrue();
        assertThat(window.getXCat()[0][8][0]).isEqualTo(1L);
        assertThat(window.getXCat()[0][9][0]).isEqualTo(2L);
    }

    @Test
    void appendAndSaveKeepsLastWindowSizeEvents() {
        List<EncodedSequenceEvent> existing = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            existing.add(event(i, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)));
        }
        when(redisCacheService.getJson(anyString(), eq(SequenceWindowState.class)))
                .thenReturn(SequenceWindowState.builder().events(existing).build());

        windowService.appendAndSave("session-1", event(10, Instant.parse("2026-01-01T00:00:10Z")));

        ArgumentCaptor<SequenceWindowState> stateCaptor = ArgumentCaptor.forClass(SequenceWindowState.class);
        verify(redisCacheService).setJson(anyString(), stateCaptor.capture(), any(Duration.class));
        assertThat(stateCaptor.getValue().getEvents()).hasSize(10);
        assertThat(stateCaptor.getValue().getEvents().get(0).getCategoricalIds()[0]).isEqualTo(1L);
        assertThat(stateCaptor.getValue().getEvents().get(9).getCategoricalIds()[0]).isEqualTo(10L);
    }

    private EncodedSequenceEvent event(long firstCategoricalId, Instant timestamp) {
        long[] cat = new long[artifactService.getSequenceMetadata().getCatCols().size()];
        float[] cont = new float[artifactService.getSequenceMetadata().getContCols().size()];
        cat[0] = firstCategoricalId;
        return EncodedSequenceEvent.builder()
                .sessionId("session-1")
                .timestamp(timestamp)
                .categoricalIds(cat)
                .continuousValues(cont)
                .schemaValid(true)
                .build();
    }
}
