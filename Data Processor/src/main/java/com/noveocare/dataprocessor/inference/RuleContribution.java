package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class RuleContribution {
    String ruleCode;
    String severity;
    double scoreContribution;
    String humanMessage;
    Map<String, Object> evidenceJson;
}
