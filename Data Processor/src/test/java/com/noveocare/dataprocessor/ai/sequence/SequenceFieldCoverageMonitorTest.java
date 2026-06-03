package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.V34TestArtifacts;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SequenceFieldCoverageMonitorTest {

    @Test
    void tracksUnknownRatiosAndHighUnknownWarnings() throws Exception {
        RuntimeArtifactService artifactService = V34TestArtifacts.loadedArtifactService();
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        RedisCacheProperties cacheProperties = new RedisCacheProperties();
        cacheProperties.setLiveStats(Duration.ofMinutes(5));
        SequenceFieldCoverageMonitor monitor = new SequenceFieldCoverageMonitor(
                artifactService,
                redisCacheService,
                cacheProperties);

        SequenceEventValues values = SequenceEventValues.builder()
                .categoricalColumns(artifactService.getSequenceMetadata().getCatCols())
                .continuousColumns(artifactService.getSequenceMetadata().getContCols())
                .categoricalValues(unknownCategoricals(artifactService))
                .continuousValuesRaw(Map.of())
                .warnings(List.of("missing_required_field_request_data_size_bytes"))
                .schemaValid(false)
                .build();
        long[] ids = new long[artifactService.getSequenceMetadata().getCatCols().size()];
        EncodedSequenceEvent encoded = EncodedSequenceEvent.builder()
                .categoricalIds(ids)
                .warnings(List.of())
                .build();

        monitor.record(values, encoded);

        Map<String, Object> snapshot = monitor.snapshot();
        assertThat(snapshot).containsEntry("totalEvents", 1L);
        assertThat(monitor.highUnknownWarnings()).contains("high_unknown_rate_api_template");
        verify(redisCacheService).setJson(anyString(), any(), any(Duration.class));
    }

    private Map<String, String> unknownCategoricals(RuntimeArtifactService artifactService) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : artifactService.getSequenceMetadata().getCatCols()) {
            values.put(field, "unknown_" + field);
        }
        return values;
    }
}
