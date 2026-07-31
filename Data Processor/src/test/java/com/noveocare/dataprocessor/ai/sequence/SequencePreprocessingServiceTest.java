package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.V34TestArtifacts;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

/**
 * Tests for SequencePreprocessingService: categorical encoding, unknown value handling,
 * and continuous column scaling for sequence model input.
 */
class SequencePreprocessingServiceTest {

    /* --- Fields --- */

    private RuntimeArtifactService artifactService;
    private SequencePreprocessingService preprocessingService;

    /* --- Setup --- */

    @BeforeEach
    void setUp() throws Exception {
        artifactService = V34TestArtifacts.loadedArtifactService();
        AiSequenceProperties sequenceProperties = new AiSequenceProperties();
        sequenceProperties.setStrictSchema(false);
        sequenceProperties.setAllowLegacyFallback(false);
        SequenceEventMapper mapper = new SequenceEventMapper(artifactService, sequenceProperties);
        SequenceFieldCoverageMonitor coverageMonitor = new SequenceFieldCoverageMonitor(
                artifactService,
                mock(RedisCacheService.class),
                new RedisCacheProperties());
        preprocessingService = new SequencePreprocessingService(
                artifactService,
                mapper,
                new SequenceValueNormalizer(),
                coverageMonitor);
    }

    /* --- Test methods: categorical encoding --- */

    @Test
    void encodesKnownCategoricalValuesAsOneBasedIds() {
        AuditTrailEvent event = eventUsingFirstVocabularyValue(Instant.parse("2026-01-05T09:30:00Z"));
        String rawPage = event.getPage();
        Integer expectedPageId = artifactService.getCategoricalVocabularies()
                .getInputIdMaps1Based()
                .get("page")
                .get(rawPage);

        EncodedSequenceEvent encoded = preprocessingService.encode(event, event.getCreatedAt().minusMillis(1_000));

        assertThat(encoded.getCategoricalIds()).hasSize(15);
        assertThat(encoded.getCategoricalIds()[0]).isEqualTo(expectedPageId.longValue());
        assertThat(encoded.targetIndex(0)).isEqualTo(expectedPageId - 1);
    }

    /* --- Test methods: unknown handling --- */

    @Test
    void mapsUnknownCategoricalValueToZeroAndSkipsInvalidTargetIndex() {
        AuditTrailEvent event = eventUsingFirstVocabularyValue(Instant.parse("2026-01-05T09:30:00Z"));
        event.setPage("not_in_training_vocab");

        EncodedSequenceEvent encoded = preprocessingService.encode(event, null);

        assertThat(encoded.getCategoricalIds()[0]).isZero();
        assertThat(encoded.targetIndex(0)).isEqualTo(-1);
        assertThat(encoded.getWarnings()).contains("unknown_category_page");
    }

    /* --- Test methods: continuous scaling --- */

    @Test
    void scalesOnlyFirstThreeContinuousColumns() {
        Instant timestamp = Instant.parse("2026-01-05T09:30:00Z");
        AuditTrailEvent event = eventUsingFirstVocabularyValue(timestamp);
        event.setRequestDataSizeBytes(2_000L);
        event.setResponseDataSizeBytes(3_000L);

        EncodedSequenceEvent encoded = preprocessingService.encode(event, timestamp.minusMillis(1_000));

        assertThat(encoded.getRawContinuousValues()).containsExactly(
                1_000.0,
                2_000.0,
                3_000.0,
                1.0,
                0.0,
                Math.sin(2.0 * Math.PI * 9.0 / 24.0),
                Math.cos(2.0 * Math.PI * 9.0 / 24.0),
                Math.sin(0.0),
                Math.cos(0.0)
        );
        assertThat(encoded.getContinuousValues()[3]).isEqualTo(1.0f);
        assertThat(encoded.getContinuousValues()[4]).isEqualTo(0.0f);
        assertThat(encoded.getContinuousValues()[5]).isCloseTo((float) Math.sin(2.0 * Math.PI * 9.0 / 24.0), within(0.0001f));
    }

    /* --- Helper methods --- */

    private AuditTrailEvent eventUsingFirstVocabularyValue(Instant timestamp) {
        AuditTrailEvent event = SequenceEventMapperTest.completeEvent(timestamp);
        for (String field : artifactService.getSequenceMetadata().getCatCols()) {
            String value = firstVocabularyKey(field);
            switch (field) {
                case "page" -> event.setPage(value);
                case "frontend_action_name" -> event.setFrontendActionName(value);
                case "api_template" -> event.setApiTemplate(value);
                case "action_value" -> event.setActionValue(value);
                case "action_type" -> event.setActionType(value);
                case "action_subtype" -> event.setActionSubtype(value);
                case "http_method" -> event.setHttpMethod(value);
                case "status" -> event.setStatus(value);
                case "device" -> event.setDevice(value);
                case "browser" -> event.setBrowser(value);
                case "os" -> event.setOs(value);
                case "ip_country" -> event.setIpCountry(value);
                case "controller" -> event.setController(value);
                case "api_family" -> event.setApiFamily(value);
                case "environment_id" -> event.setEnvironmentId(value);
                default -> throw new IllegalArgumentException("Unexpected field " + field);
            }
        }
        return event;
    }

    private String firstVocabularyKey(String field) {
        Map<String, Integer> values = artifactService.getCategoricalVocabularies().getInputIdMaps1Based().get(field);
        return values.keySet().iterator().next();
    }

    private org.assertj.core.data.Offset<Float> within(float value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
