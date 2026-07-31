package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.artifact.AnomalyScoreConfig;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.artifact.SequenceMetadata;
import com.noveocare.dataprocessor.config.AiRiskScoringProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for SequenceAnomalyScoringService: softmax NLL scoring, continuous error calculation,
 * context weighting, and handling of unknown target values.
 */
class SequenceAnomalyScoringServiceTest {

    /* --- Test methods --- */

    @Test
    void appliesSoftmaxNllWeightsAndContinuousErrors() {
        RuntimeArtifactService artifactService = mock(RuntimeArtifactService.class);
        SequenceMetadata metadata = new SequenceMetadata();
        metadata.setCatCols(List.of("page"));
        metadata.setContCols(List.of(
                "time_since_prev_action_ms",
                "request_data_size_bytes",
                "response_data_size_bytes",
                "is_business_hours",
                "is_weekend",
                "hour_sin",
                "hour_cos",
                "dow_sin",
                "dow_cos"));
        AnomalyScoreConfig config = new AnomalyScoreConfig();
        config.setCatScoreWeightsByColumn(Map.of("page", 2.0));
        config.setCatScoreNormByColumn(Map.of("page", 0.5));
        config.setContScoreW(2.0);
        config.setCtxScoreW(0.5);
        when(artifactService.getSequenceMetadata()).thenReturn(metadata);
        when(artifactService.getAnomalyScoreConfig()).thenReturn(config);

        AiRiskScoringProperties riskProperties = new AiRiskScoringProperties();
        riskProperties.setAiScoreScale(5.0);
        SequenceAnomalyScoringService scoringService = new SequenceAnomalyScoringService(artifactService, riskProperties);

        SequenceInferenceResult inference = SequenceInferenceResult.builder()
                .modelArtifact("transformer_sequence_engine.onnx")
                .categoricalLogits(List.of(new float[]{2.0f, 0.0f}))
                .continuousPrediction(new float[]{0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f})
                .build();
        EncodedSequenceEvent target = EncodedSequenceEvent.builder()
                .categoricalIds(new long[]{1L})
                .continuousValues(new float[]{1.0f, 2.0f, 3.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f})
                .rawCategoricalValues(Map.of("page", "home"))
                .warnings(List.of())
                .build();

        SequenceScoreResult result = scoringService.score(inference, target);

        double expectedNll = Math.log(Math.exp(2.0) + Math.exp(0.0)) - 2.0;
        double expectedNumericError = (1.0 + 2.0 + 3.0) / 3.0;
        double expectedScore = expectedNll + 2.0 * expectedNumericError;
        assertThat(result.getCategoricalScore()).isCloseTo(expectedNll, within(0.0001));
        assertThat(result.getContinuousScore()).isCloseTo(expectedNumericError, within(0.0001));
        assertThat(result.getSequenceAnomalyScore()).isCloseTo(expectedScore, within(0.0001));
        assertThat(result.getTopContributingFields()).hasSize(1);
    }

    @Test
    void skipsUnknownTargetWithoutInvalidLogitIndexing() {
        RuntimeArtifactService artifactService = mock(RuntimeArtifactService.class);
        SequenceMetadata metadata = new SequenceMetadata();
        metadata.setCatCols(List.of("page"));
        metadata.setContCols(List.of("time_since_prev_action_ms"));
        when(artifactService.getSequenceMetadata()).thenReturn(metadata);
        when(artifactService.getAnomalyScoreConfig()).thenReturn(new AnomalyScoreConfig());

        SequenceAnomalyScoringService scoringService = new SequenceAnomalyScoringService(
                artifactService,
                new AiRiskScoringProperties());

        SequenceScoreResult result = scoringService.score(
                SequenceInferenceResult.builder()
                        .categoricalLogits(List.of(new float[]{1.0f}))
                        .continuousPrediction(new float[]{0.0f})
                        .build(),
                EncodedSequenceEvent.builder()
                        .categoricalIds(new long[]{0L})
                        .continuousValues(new float[]{0.0f})
                        .warnings(List.of())
                        .build());

        assertThat(result.getCategoricalScore()).isZero();
        assertThat(result.getWarnings()).contains("unknown_target_for_field_page");
    }

    /* --- Helper methods --- */

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
