package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO holding evidence produced by rule-based evaluation.
 * <p>
 * Contains the aggregate rule risk score, the list of rule codes that
 * triggered, and a map of per-rule contribution details.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36RuleEvidenceDto {
    /** Aggregate risk score from rule-based evaluation. */
    private Double ruleRiskScore;
    /** List of rule codes that triggered during evaluation. */
    private List<String> triggeredRules;
    /** Map of rule codes to their individual contribution/impact details. */
    private Map<String, Object> ruleContributions;
}
