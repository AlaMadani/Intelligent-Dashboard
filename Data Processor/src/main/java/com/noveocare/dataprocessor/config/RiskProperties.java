package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Thresholds and label lists used when computing user risk tiers.
 */
@Data
@ConfigurationProperties(prefix = "app.risk")
public class RiskProperties {
    private double mediumThreshold;
    private double highThreshold;
    private List<String> highRiskTypes = new ArrayList<>();
}
