package com.noveocare.dataprocessor.inference;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Represents one triggered rule's contribution to the overall rule risk score,
 * including severity level, numeric contribution, and contextual evidence.
 */
@Value
@Builder
public class RuleContribution {

    /* ---- Rule contribution fields ---- */
    String ruleCode;
    String severity;
    double scoreContribution;
    String humanMessage;
    Map<String, Object> evidenceJson;
}
