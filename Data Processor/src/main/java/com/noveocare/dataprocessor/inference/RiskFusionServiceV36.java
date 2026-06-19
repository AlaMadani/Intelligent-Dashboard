package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.ai.tabular.TabularAnomalyResult;
import com.noveocare.dataprocessor.config.AiRiskFusionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RiskFusionServiceV36 {
    private final AiRiskFusionProperties properties;

    public RiskFusionResult fuse(TabularAnomalyResult tabular,
                                 Double transformerRiskScore100,
                                 Double tcnRiskScore100,
                                 RuleRiskResult rules,
                                 double aggregationBoost) {
        Map<String, Double> configuredWeights = configuredWeights();
        Map<String, Double> usedWeights = new LinkedHashMap<>();
        Map<String, Double> unavailableWeights = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Double> mlScores = new LinkedHashMap<>();
        mlScores.put("xgboost", tabular == null ? null : tabular.getXgboostAnomalyScore100());
        mlScores.put("lightgbm", tabular == null ? null : tabular.getLightgbmAlertScore100());
        mlScores.put("transformer", transformerRiskScore100);
        mlScores.put("tcn", tcnRiskScore100);

        double availableMlWeight = 0.0;
        double missingMlWeight = 0.0;
        for (Map.Entry<String, Double> entry : mlScores.entrySet()) {
            double weight = configuredWeights.get(entry.getKey());
            if (entry.getValue() == null) {
                unavailableWeights.put(entry.getKey(), weight);
                missingMlWeight += weight;
            } else {
                availableMlWeight += weight;
            }
        }

        for (Map.Entry<String, Double> entry : mlScores.entrySet()) {
            double weight = configuredWeights.get(entry.getKey());
            if (entry.getValue() != null && properties.isRenormalizeMissingModelWeights() && availableMlWeight > 0.0) {
                weight += missingMlWeight * (weight / availableMlWeight);
            }
            if (entry.getValue() != null) {
                usedWeights.put(entry.getKey(), weight);
            }
        }
        usedWeights.put("rules", properties.getRulesWeight());
        if (properties.getBusinessContextWeight() > 0.0) {
            usedWeights.put("business_context", properties.getBusinessContextWeight());
        }

        double ruleRisk = rules == null ? 0.0 : rules.getRuleRiskScore();
        double businessContext = rules == null ? 0.0 : rules.getBusinessContextScore();
        double xgbContribution = contribution(mlScores.get("xgboost"), usedWeights.get("xgboost"));
        double lgbmContribution = contribution(mlScores.get("lightgbm"), usedWeights.get("lightgbm"));
        double transformerContribution = contribution(mlScores.get("transformer"), usedWeights.get("transformer"));
        double tcnContribution = contribution(mlScores.get("tcn"), usedWeights.get("tcn"));
        double ruleContribution = ruleRisk * properties.getRulesWeight();
        double businessContribution = businessContext * properties.getBusinessContextWeight();

        double finalRisk;
        String fallbackMode;
        if (availableMlWeight == 0.0) {
            finalRisk = ruleRisk + aggregationBoost;
            fallbackMode = "RULES_ONLY";
            warnings.add("all_ml_models_unavailable_rules_only_fusion");
        } else {
            finalRisk = xgbContribution + lgbmContribution + transformerContribution + tcnContribution
                    + ruleContribution + businessContribution + aggregationBoost;
            fallbackMode = fallbackMode(unavailableWeights, mlScores);
        }
        finalRisk = clamp(finalRisk, 0.0, 100.0);
        return RiskFusionResult.builder()
                .finalRiskScore(finalRisk)
                .riskLevel(riskLevel(finalRisk))
                .riskScale("ZERO_TO_ONE_HUNDRED")
                .xgboostContribution(xgbContribution)
                .lightgbmContribution(lgbmContribution)
                .transformerContribution(transformerContribution)
                .tcnContribution(tcnContribution)
                .ruleContribution(ruleContribution)
                .businessContextContribution(businessContribution)
                .aggregationBoost(aggregationBoost)
                .usedWeights(usedWeights)
                .unavailableModelWeights(unavailableWeights)
                .fallbackMode(fallbackMode)
                .fusionWarnings(warnings)
                .modelContributions(List.of(
                        modelContribution("xgboost", mlScores.get("xgboost"), usedWeights.get("xgboost"), xgbContribution),
                        modelContribution("lightgbm", mlScores.get("lightgbm"), usedWeights.get("lightgbm"), lgbmContribution),
                        modelContribution("transformer", mlScores.get("transformer"), usedWeights.get("transformer"), transformerContribution),
                        modelContribution("tcn", mlScores.get("tcn"), usedWeights.get("tcn"), tcnContribution),
                        modelContribution("rules", ruleRisk, properties.getRulesWeight(), ruleContribution)))
                .build();
    }

    private Map<String, Double> configuredWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("xgboost", properties.getXgboostWeight());
        weights.put("lightgbm", properties.getLightgbmWeight());
        weights.put("transformer", properties.getTransformerWeight());
        weights.put("tcn", properties.getTcnWeight());
        return weights;
    }

    private double contribution(Double score, Double weight) {
        return score == null || weight == null ? 0.0 : score * weight;
    }

    private ModelContribution modelContribution(String model, Double score, Double weight, double contribution) {
        return ModelContribution.builder()
                .modelName(model)
                .score100(score)
                .weight(weight == null ? 0.0 : weight)
                .contribution(contribution)
                .available(score != null)
                .build();
    }

    private String fallbackMode(Map<String, Double> unavailableWeights, Map<String, Double> mlScores) {
        if (unavailableWeights.isEmpty()) {
            return "FULL_HYBRID";
        }
        if (mlScores.get("transformer") == null && mlScores.get("tcn") == null) {
            return "TABULAR_RULES";
        }
        if (mlScores.get("xgboost") == null && mlScores.get("lightgbm") == null) {
            return "SEQUENCE_RULES";
        }
        return "PARTIAL_HYBRID";
    }

    private String riskLevel(double score) {
        if (score >= properties.getCriticalThreshold()) {
            return "CRITICAL";
        }
        if (score >= properties.getHighThreshold()) {
            return "HIGH";
        }
        if (score >= properties.getMediumThreshold()) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
