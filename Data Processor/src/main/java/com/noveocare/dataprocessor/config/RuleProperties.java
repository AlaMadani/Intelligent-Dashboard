package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the deterministic anomaly rules that run before ML inference.
 */
@Data
@ConfigurationProperties(prefix = "app.rules")
public class RuleProperties {
    // Hour window, login constraints, and repeated-failure parameters for tier-1 detection.
    private UnusualHour unusualHour = new UnusualHour();
    private SkipLogin skipLogin = new SkipLogin();
    private RepeatedFail repeatedFail = new RepeatedFail();
    private List<String> sessionEndActions = new ArrayList<>();
    private int tier2EveryEvents = 5;

    @Data
    public static class UnusualHour {
        // Inclusive UTC hour range considered suspicious.
        private int start;
        private int end;
    }

    @Data
    public static class SkipLogin {
        // First-session actions that are acceptable without being treated as a skipped login.
        private List<String> allowedActions = new ArrayList<>();
    }

    @Data
    public static class RepeatedFail {
        // Event types and minimum KO streak length that trigger the rule.
        private List<String> types = new ArrayList<>();
        private int consecutive;
    }
}
