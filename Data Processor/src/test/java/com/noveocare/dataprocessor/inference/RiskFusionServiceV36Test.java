package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyResult;
import com.noveocare.dataprocessor.config.AiRiskFusionProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskFusionServiceV36Test {

    private final RiskFusionServiceV36 service = new RiskFusionServiceV36(new AiRiskFusionProperties());

    @Test
    void fusesAllAvailableModels() {
        RiskFusionResult result = service.fuse(
                TabularAnomalyResult.builder()
                        .xgboostAnomalyScore100(90.0)
                        .lightgbmAlertScore100(80.0)
                        .build(),
                70.0,
                60.0,
                rules(50.0),
                0.0);

        assertThat(result.getFinalRiskScore()).isCloseTo(74.5, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getFallbackMode()).isEqualTo("FULL_HYBRID");
    }

    @Test
    void renormalizesMissingXGBoostAcrossAvailableMlModels() {
        RiskFusionResult result = service.fuse(
                TabularAnomalyResult.builder()
                        .lightgbmAlertScore100(80.0)
                        .build(),
                70.0,
                60.0,
                rules(50.0),
                0.0);

        assertThat(result.getUnavailableModelWeights()).containsKey("xgboost");
        assertThat(result.getUsedWeights().get("lightgbm")).isGreaterThan(0.25);
        assertThat(result.getFallbackMode()).isEqualTo("PARTIAL_HYBRID");
    }

    @Test
    void renormalizesMissingLightGbmAcrossAvailableMlModels() {
        RiskFusionResult result = service.fuse(
                TabularAnomalyResult.builder()
                        .xgboostAnomalyScore100(90.0)
                        .build(),
                70.0,
                60.0,
                rules(50.0),
                0.0);

        assertThat(result.getUnavailableModelWeights()).containsKey("lightgbm");
        assertThat(result.getUsedWeights().get("xgboost")).isGreaterThan(0.30);
        assertThat(result.getRuleContribution()).isEqualTo(7.5);
        assertThat(result.getFallbackMode()).isEqualTo("PARTIAL_HYBRID");
    }

    @Test
    void usesSequenceAndRulesWhenBothTabularModelsUnavailable() {
        RiskFusionResult result = service.fuse(
                TabularAnomalyResult.builder().build(),
                75.0,
                65.0,
                rules(60.0),
                0.0);

        assertThat(result.getUnavailableModelWeights()).containsKeys("xgboost", "lightgbm");
        assertThat(result.getTransformerContribution()).isGreaterThan(0.0);
        assertThat(result.getTcnContribution()).isGreaterThan(0.0);
        assertThat(result.getRuleContribution()).isEqualTo(9.0);
        assertThat(result.getFallbackMode()).isEqualTo("SEQUENCE_RULES");
    }

    @Test
    void usesTcnWhenTransformerUnavailable() {
        RiskFusionResult result = service.fuse(
                TabularAnomalyResult.builder()
                        .xgboostAnomalyScore100(90.0)
                        .lightgbmAlertScore100(80.0)
                        .build(),
                null,
                65.0,
                rules(50.0),
                0.0);

        assertThat(result.getUnavailableModelWeights()).containsKey("transformer");
        assertThat(result.getTcnContribution()).isGreaterThan(0.0);
        assertThat(result.getFallbackMode()).isEqualTo("PARTIAL_HYBRID");
    }

    @Test
    void usesTabularAndRulesWhenBothSequenceModelsUnavailable() {
        RiskFusionResult result = service.fuse(
                TabularAnomalyResult.builder()
                        .xgboostAnomalyScore100(90.0)
                        .lightgbmAlertScore100(80.0)
                        .build(),
                null,
                null,
                rules(50.0),
                0.0);

        assertThat(result.getUnavailableModelWeights()).containsKeys("transformer", "tcn");
        assertThat(result.getXgboostContribution()).isGreaterThan(0.0);
        assertThat(result.getLightgbmContribution()).isGreaterThan(0.0);
        assertThat(result.getFallbackMode()).isEqualTo("TABULAR_RULES");
    }

    @Test
    void fallsBackToRulesOnlyWhenMlUnavailable() {
        RiskFusionResult result = service.fuse(
                TabularAnomalyResult.unavailable(List.of("missing")),
                null,
                null,
                rules(65.0),
                5.0);

        assertThat(result.getFinalRiskScore()).isEqualTo(70.0);
        assertThat(result.getRuleContribution()).isEqualTo(9.75);
        assertThat(result.getFallbackMode()).isEqualTo("RULES_ONLY");
    }

    @Test
    void keepsFusionInputsOnZeroToHundredScaleAndContributionsAsRiskPoints() {
        RiskFusionResult result = service.fuse(
                TabularAnomalyResult.builder()
                        .xgboostAnomalyScore(0.91)
                        .xgboostAnomalyScore100(91.0)
                        .lightgbmAlertScore(0.88)
                        .lightgbmAlertScore100(88.0)
                        .oneClassSvmNoveltyScoreRaw(2.4)
                        .build(),
                79.0,
                74.0,
                rules(85.0),
                0.0);

        assertThat(result.getXgboostContribution()).isCloseTo(27.3, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.getLightgbmContribution()).isCloseTo(22.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.getTransformerContribution()).isCloseTo(15.8, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.getTcnContribution()).isCloseTo(7.4, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.getRuleContribution()).isCloseTo(12.75, org.assertj.core.data.Offset.offset(0.0001));
    }

    private RuleRiskResult rules(double score) {
        return RuleRiskResult.builder()
                .ruleRiskScore(score)
                .triggeredRules(List.of("OFF_HOURS_ACCESS"))
                .ruleContributions(List.of())
                .ruleEvidence(java.util.Map.of())
                .businessContextScore(0.0)
                .build();
    }
}
