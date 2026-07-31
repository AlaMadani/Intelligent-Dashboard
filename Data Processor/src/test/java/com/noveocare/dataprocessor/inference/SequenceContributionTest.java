package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyResult;
import com.noveocare.dataprocessor.config.AiRiskFusionProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for sequence model contribution logic in RiskFusionServiceV36:
 * transformer/TCN availability, weight renormalization, and regression
 * coverage for duplicate sequence contribution bugs.
 */
class SequenceContributionTest {

    /* --- Fields --- */

    private final AiRiskFusionProperties properties = new AiRiskFusionProperties();
    private final RiskFusionServiceV36 service = new RiskFusionServiceV36(properties);

    /* --- Test methods: single sequence model --- */

    @Test
    void onlyTransformerContributesWhenTcnIsNull() {
        RiskFusionResult result = service.fuse(
                tabular(10.0, 10.0),
                90.0,
                null,
                rules(0.0),
                0.0);

        assertThat(result.getTransformerContribution()).isCloseTo(20.4, offset(0.01));
        assertThat(result.getTcnContribution()).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void onlyTcnContributesWhenTransformerIsNull() {
        RiskFusionResult result = service.fuse(
                tabular(10.0, 10.0),
                null,
                80.0,
                rules(0.0),
                0.0);

        assertThat(result.getTransformerContribution()).isCloseTo(0.0, offset(0.001));
        assertThat(result.getTcnContribution()).isCloseTo(10.46, offset(0.01));
    }

    /* --- Test methods: both sequence models --- */

    @Test
    void bothContributeWhenBothProvided() {
        RiskFusionResult result = service.fuse(
                tabular(10.0, 10.0),
                90.0,
                80.0,
                rules(0.0),
                0.0);

        assertThat(result.getTransformerContribution()).isCloseTo(18.0, offset(0.001));
        assertThat(result.getTcnContribution()).isCloseTo(8.0, offset(0.001));
    }

    /* --- Test methods: weight renormalization --- */

    @Test
    void transformerWeightIsRenormalizedAcrossAllAvailableWhenTcnMissing() {
        RiskFusionResult result = service.fuse(
                tabular(10.0, 10.0),
                90.0,
                null,
                rules(0.0),
                0.0);

        double renormalizedWeight = result.getUsedWeights().get("transformer");
        double expected = 0.20 + 0.10 * (0.20 / (0.30 + 0.25 + 0.20));
        assertThat(renormalizedWeight).isCloseTo(expected, offset(0.001));
        assertThat(result.getTcnContribution()).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void tcnWeightIsRenormalizedAcrossAllAvailableWhenTransformerMissing() {
        RiskFusionResult result = service.fuse(
                tabular(10.0, 10.0),
                null,
                80.0,
                rules(0.0),
                0.0);

        double renormalizedWeight = result.getUsedWeights().get("tcn");
        double expected = 0.10 + 0.20 * (0.10 / (0.30 + 0.25 + 0.10));
        assertThat(renormalizedWeight).isCloseTo(expected, offset(0.001));
        assertThat(result.getTransformerContribution()).isCloseTo(0.0, offset(0.001));
    }

    /* --- Test methods: contribution list --- */

    @Test
    void modelContributionsListReflectsCorrectAvailability() {
        RiskFusionResult result = service.fuse(
                tabular(10.0, 10.0),
                90.0,
                null,
                rules(0.0),
                0.0);

        List<ModelContribution> contributions = result.getModelContributions();
        ModelContribution transformer = contributions.stream()
                .filter(c -> "transformer".equals(c.getModelName()))
                .findFirst().orElseThrow();
        ModelContribution tcn = contributions.stream()
                .filter(c -> "tcn".equals(c.getModelName()))
                .findFirst().orElseThrow();

        assertThat(transformer.isAvailable()).isTrue();
        assertThat(transformer.getScore100()).isEqualTo(90.0);
        assertThat(transformer.getContribution()).isGreaterThan(0.0);

        assertThat(tcn.isAvailable()).isFalse();
        assertThat(tcn.getScore100()).isNull();
        assertThat(tcn.getContribution()).isCloseTo(0.0, offset(0.001));
    }

    /* --- Test methods: edge cases --- */

    @Test
    void finalRiskNotDoubleCountedWithBothNull() {
        RiskFusionResult result = service.fuse(
                tabular(0.0, 0.0),
                null,
                null,
                rules(50.0),
                0.0);

        double ruleContrib = 50.0 * properties.getRulesWeight();
        assertThat(result.getFinalRiskScore()).isCloseTo(ruleContrib, offset(0.001));
        assertThat(result.getTransformerContribution()).isCloseTo(0.0, offset(0.001));
        assertThat(result.getTcnContribution()).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void sameScoreBothModelsBothContributeWithDifferentWeights() {
        double sameScore = 99.59;
        RiskFusionResult result = service.fuse(
                tabular(0.0, 0.0),
                sameScore,
                sameScore,
                rules(35.0),
                0.0);

        double expectedTransformer = 99.59 * 0.20;
        double expectedTcn = 99.59 * 0.10;
        double expectedRule = 35.0 * properties.getRulesWeight();
        assertThat(result.getTransformerContribution()).isCloseTo(expectedTransformer, offset(0.01));
        assertThat(result.getTcnContribution()).isCloseTo(expectedTcn, offset(0.01));
        assertThat(result.getFinalRiskScore()).isCloseTo(expectedTransformer + expectedTcn + expectedRule, offset(0.01));
    }

    @Test
    void tcnUnavailableWhenOnlyTransformerRuns() {
        RiskFusionResult result = service.fuse(
                tabular(0.0, 0.0),
                99.59,
                null,
                rules(35.0),
                0.0);

        List<ModelContribution> contributions = result.getModelContributions();
        ModelContribution tcn = contributions.stream()
                .filter(c -> "tcn".equals(c.getModelName()))
                .findFirst().orElseThrow();

        assertThat(tcn.isAvailable()).isFalse();
        assertThat(tcn.getScore100()).isNull();
    }

    @Test
    void finalRiskIsLowWhenOnlyTransformerRunsAndRuleScore35()
            throws Exception {
        RiskFusionResult result = service.fuse(
                tabular(0.0, 0.0),
                99.59,
                null,
                rules(35.0),
                0.0);

        double expectedTransformerContrib = 99.59 * 0.20;
        double expectedTcnContrib = 0.0;
        double expectedRuleContrib = 35.0 * properties.getRulesWeight();

        // With renormalization: 0.20 + 0.10 * (0.20 / (0.30 + 0.25 + 0.20))
        double renormalized = 0.20 + 0.10 * (0.20 / (0.30 + 0.25 + 0.20));
        double expectedRenormalizedTransformerContrib = 99.59 * renormalized;

        assertThat(result.getTransformerContribution())
                .describedAs("transformer contribution should use renormalized weight when tcn missing")
                .isCloseTo(expectedRenormalizedTransformerContrib, offset(0.01));
        assertThat(result.getTcnContribution())
                .describedAs("tcn contribution must be 0.0 when tcn did not run")
                .isCloseTo(0.0, offset(0.001));
        assertThat(result.getFinalRiskScore())
                .describedAs("finalRisk must be LOW (< 35.0) when only transformer contributes, not MEDIUM")
                .isLessThan(35.0);
        assertThat(result.getRiskLevel())
                .describedAs("riskTier must be LOW when tcn does not contribute")
                .isEqualTo("LOW");
    }

    @Test
    void finalRiskIsLowWhenOnlyTcnRunsAndRuleScore35()
            throws Exception {
        RiskFusionResult result = service.fuse(
                tabular(0.0, 0.0),
                null,
                99.59,
                rules(35.0),
                0.0);

        double expectedTcnContrib = 99.59 * 0.10;
        double expectedRuleContrib = 35.0 * properties.getRulesWeight();

        double renormalized = 0.10 + 0.20 * (0.10 / (0.30 + 0.25 + 0.10));
        double expectedRenormalizedTcnContrib = 99.59 * renormalized;

        assertThat(result.getTcnContribution())
                .describedAs("tcn contribution should use renormalized weight when transformer missing")
                .isCloseTo(expectedRenormalizedTcnContrib, offset(0.01));
        assertThat(result.getTransformerContribution())
                .describedAs("transformer contribution must be 0.0 when transformer did not run")
                .isCloseTo(0.0, offset(0.001));
        assertThat(result.getFinalRiskScore())
                .describedAs("finalRisk must be < 35.0 when only tcn contributes")
                .isLessThan(35.0);
        assertThat(result.getRiskLevel())
                .describedAs("riskTier must be LOW when transformer does not contribute")
                .isEqualTo("LOW");
    }

    /* --- Test methods: regression --- */

    @Test
    void duplicateSequenceContributionBugRegression()
            throws Exception {
        double transformerScore = 99.59;
        double ruleScore = 35.0;

        RiskFusionResult bugResult = service.fuse(
                tabular(0.0, 0.0),
                transformerScore,
                transformerScore, // BUG: tcn gets same score as transformer
                rules(ruleScore),
                0.0);

        RiskFusionResult fixedResult = service.fuse(
                tabular(0.0, 0.0),
                transformerScore,
                null, // FIX: tcn is null when not run
                rules(ruleScore),
                0.0);

        assertThat(bugResult.getFinalRiskScore())
                .describedAs("BUG: both transformer and tcn same score -> inflated finalRisk >= 35 (MEDIUM)")
                .isGreaterThanOrEqualTo(35.0);
        assertThat(bugResult.getRiskLevel())
                .describedAs("BUG: inflated finalRisk triggers MEDIUM alert incorrectly")
                .isEqualTo("MEDIUM");

        assertThat(fixedResult.getFinalRiskScore())
                .describedAs("FIX: tcn null -> lower finalRisk < 35 (LOW)")
                .isLessThan(35.0);
        assertThat(fixedResult.getRiskLevel())
                .describedAs("FIX: correct risk tier is LOW, not MEDIUM")
                .isEqualTo("LOW");

        assertThat(fixedResult.getFinalRiskScore())
                .describedAs("FIX: finalRisk must be lower than bug version (because tcnContribution=0)")
                .isLessThan(bugResult.getFinalRiskScore());
    }

    /* --- Helper methods --- */

    private static org.assertj.core.data.Offset<Double> offset(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }

    private TabularAnomalyResult tabular(double xgb, double lgbm) {
        return TabularAnomalyResult.builder()
                .xgboostAnomalyScore100(xgb)
                .lightgbmAlertScore100(lgbm)
                .build();
    }

    private RuleRiskResult rules(double score) {
        return RuleRiskResult.builder()
                .ruleRiskScore(score)
                .triggeredRules(List.of())
                .ruleContributions(List.of())
                .ruleEvidence(java.util.Map.of())
                .businessContextScore(0.0)
                .build();
    }
}
