package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyResult;
import com.noveocare.dataprocessor.config.AiRiskFusionProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for risk tier boundary logic: verifies correct risk level assignment
 * across the full [0..100] score range including edge cases and clamping.
 */
class RiskTierBoundaryTest {

    /* --- Fields --- */

    private final AiRiskFusionProperties properties = new AiRiskFusionProperties();
    private final RiskFusionServiceV36 service = new RiskFusionServiceV36(properties);

    /* --- Test methods: LOW tier --- */

    @Test
    void below35IsLOW() {
        RiskFusionResult result = fuseWithFinal(34.9);
        assertThat(result.getRiskLevel()).isEqualTo("LOW");
    }

    /* --- Test methods: MEDIUM tier --- */

    @Test
    void exactly35IsMEDIUM() {
        RiskFusionResult result = fuseWithFinal(35.0);
        assertThat(result.getRiskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void below60IsMEDIUM() {
        RiskFusionResult result = fuseWithFinal(59.9);
        assertThat(result.getRiskLevel()).isEqualTo("MEDIUM");
    }

    /* --- Test methods: HIGH tier --- */

    @Test
    void exactly60IsHIGH() {
        RiskFusionResult result = fuseWithFinal(60.0);
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
    }

    @Test
    void below80IsHIGH() {
        RiskFusionResult result = fuseWithFinal(79.9);
        assertThat(result.getRiskLevel()).isEqualTo("HIGH");
    }

    /* --- Test methods: CRITICAL tier --- */

    @Test
    void exactly80IsCRITICAL() {
        RiskFusionResult result = fuseWithFinal(80.0);
        assertThat(result.getRiskLevel()).isEqualTo("CRITICAL");
    }

    @Test
    void above80IsCRITICAL() {
        RiskFusionResult result = fuseWithFinal(100.0);
        assertThat(result.getRiskLevel()).isEqualTo("CRITICAL");
    }

    /* --- Test methods: scale and clamping --- */

    @Test
    void resultHasRiskScale() {
        RiskFusionResult result = fuseWithFinal(50.0);
        assertThat(result.getRiskScale()).isEqualTo("ZERO_TO_ONE_HUNDRED");
    }

    @Test
    void zeroRiskIsLOW() {
        RiskFusionResult result = fuseWithFinal(0.0);
        assertThat(result.getRiskLevel()).isEqualTo("LOW");
    }

    @Test
    void negativeRiskClampedToZero() {
        RiskFusionResult result = service.fuse(
                TabularAnomalyResult.builder()
                        .xgboostAnomalyScore100(-10.0)
                        .lightgbmAlertScore100(-5.0)
                        .build(),
                0.0, null,
                rules(0.0), 0.0);
        assertThat(result.getFinalRiskScore()).isEqualTo(0.0);
        assertThat(result.getRiskLevel()).isEqualTo("LOW");
    }

    @Test
    void over100RiskClamped() {
        RiskFusionResult result = fuseWithFinal(150.0);
        assertThat(result.getFinalRiskScore()).isEqualTo(100.0);
        assertThat(result.getRiskLevel()).isEqualTo("CRITICAL");
    }

    /* --- Helper methods --- */

    private RiskFusionResult fuseWithFinal(double finalRiskScore) {
        double seqScore = finalRiskScore;
        return service.fuse(
                TabularAnomalyResult.builder()
                        .xgboostAnomalyScore100(finalRiskScore)
                        .lightgbmAlertScore100(finalRiskScore)
                        .build(),
                seqScore, null,
                rules(finalRiskScore), 0.0);
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
