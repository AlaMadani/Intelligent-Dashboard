package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder(toBuilder = true)
public class RuleRiskResult {
    double ruleRiskScore;
    List<String> triggeredRules;
    List<RuleContribution> ruleContributions;
    Map<String, Object> ruleEvidence;
    double businessContextScore;
}
