package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Aggregated outcome of rule-risk evaluation: the total rule score, the list of
 * triggered rule codes, per-rule contributions, raw evidence, and a business
 * context sub-score.
 */
@Value
@Builder(toBuilder = true)
public class RuleRiskResult {

    /* ---- Rule risk result fields ---- */
    double ruleRiskScore;
    List<String> triggeredRules;
    List<RuleContribution> ruleContributions;
    Map<String, Object> ruleEvidence;
    double businessContextScore;
}
