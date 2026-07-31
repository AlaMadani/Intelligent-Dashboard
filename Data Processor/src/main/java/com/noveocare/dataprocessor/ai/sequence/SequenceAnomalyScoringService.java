package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.artifact.AnomalyScoreConfig;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiRiskScoringProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Computes anomaly scores from sequence model logits (categorical NLL and
 * continuous MAE) weighted by AnomalyScoreConfig, producing a 0-100 risk score.
 */
@Service
@RequiredArgsConstructor
public class SequenceAnomalyScoringService {

    /* ---- Dependencies ---- */
    private final RuntimeArtifactService artifactService;
    private final AiRiskScoringProperties riskProperties;

    /* ========== Public API ========== */

    /* Scores a single inference result against the target event. */
    public SequenceScoreResult score(SequenceInferenceResult inference, EncodedSequenceEvent target) {
        AnomalyScoreConfig config = artifactService.getAnomalyScoreConfig();
        List<String> catCols = artifactService.getSequenceMetadata().getCatCols();
        List<String> contCols = artifactService.getSequenceMetadata().getContCols();
        List<String> warnings = new ArrayList<>(target.getWarnings() == null ? List.of() : target.getWarnings());
        List<SequenceFieldContribution> contributions = new ArrayList<>();

        double categoricalScore = 0.0;
        for (int i = 0; i < catCols.size(); i++) {
            String field = catCols.get(i);
            int targetIndex = target.targetIndex(i);
            long encodedId = target.getCategoricalIds() == null || i >= target.getCategoricalIds().length
                    ? 0L
                    : target.getCategoricalIds()[i];
            if (targetIndex < 0) {
                warnings.add("unknown_target_for_field_" + field);
                continue;
            }
            if (i >= inference.getCategoricalLogits().size()) {
                warnings.add("missing_logits_for_field_" + field);
                continue;
            }
            float[] logits = inference.getCategoricalLogits().get(i);
            if (targetIndex >= logits.length) {
                warnings.add("target_index_out_of_range_" + field);
                continue;
            }
            double nll = negativeLogSoftmax(logits, targetIndex);
            double weight = config.getCatScoreWeightsByColumn().getOrDefault(field, 1.0);
            double norm = config.getCatScoreNormByColumn().getOrDefault(field, 1.0);
            double contribution = nll * weight * norm;
            categoricalScore += contribution;
            contributions.add(SequenceFieldContribution.builder()
                    .field(field)
                    .contribution(contribution)
                    .nll(nll)
                    .targetIndex(targetIndex)
                    .encodedId(encodedId)
                    .rawValue(target.getRawCategoricalValues() == null ? null : target.getRawCategoricalValues().get(field))
                    .build());
        }

        double numericError = meanAbsoluteError(inference.getContinuousPrediction(), target.getContinuousValues(), 0, 3);
        double contextError = meanAbsoluteError(inference.getContinuousPrediction(), target.getContinuousValues(), 3, contCols.size());
        double sequenceScore = categoricalScore
                + config.getContScoreW() * numericError
                + config.getCtxScoreW() * contextError;
        double aiRiskScore = 100.0 * (1.0 - Math.exp(-sequenceScore / Math.max(0.000001, riskProperties.getAiScoreScale())));

        List<SequenceFieldContribution> top = contributions.stream()
                .sorted(Comparator.comparing(SequenceFieldContribution::getContribution, Comparator.nullsLast(Double::compareTo)).reversed())
                .limit(5)
                .toList();

        return SequenceScoreResult.builder()
                .available(true)
                .modelKind(inference.getModelKind() == null ? null : inference.getModelKind().name().toLowerCase(java.util.Locale.ROOT))
                .modelArtifact(inference.getModelArtifact())
                .sequenceAnomalyScore(sequenceScore)
                .categoricalScore(categoricalScore)
                .continuousScore(numericError)
                .contextScore(contextError)
                .aiRiskScore(clamp(aiRiskScore, 0.0, 100.0))
                .latencyMillis(inference.getLatencyMillis())
                .perFieldContributions(List.copyOf(contributions))
                .topContributingFields(top)
                .warnings(List.copyOf(warnings))
                .build();
    }

    /* ========== Private helpers ========== */

    /* Computes the negative log-softmax for a target class index. */
    private double negativeLogSoftmax(float[] logits, int targetIndex) {
        double max = Double.NEGATIVE_INFINITY;
        for (float logit : logits) {
            max = Math.max(max, logit);
        }
        double sum = 0.0;
        for (float logit : logits) {
            sum += Math.exp(logit - max);
        }
        double logSumExp = max + Math.log(sum);
        return logSumExp - logits[targetIndex];
    }

    /* Computes the mean absolute error over a slice of the prediction/target. */
    private double meanAbsoluteError(float[] prediction, float[] target, int startInclusive, int endExclusive) {
        if (prediction == null || target == null || startInclusive >= endExclusive) {
            return 0.0;
        }
        double total = 0.0;
        int count = 0;
        int end = Math.min(Math.min(prediction.length, target.length), endExclusive);
        for (int i = startInclusive; i < end; i++) {
            total += Math.abs(prediction[i] - target[i]);
            count++;
        }
        return count == 0 ? 0.0 : total / count;
    }

    /* Clamps a value to [min, max]. */
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
