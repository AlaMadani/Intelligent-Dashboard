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

    // Extra heuristics that extend tier-1 detection beyond the initial static checks.
    private RapidFire rapidFire = new RapidFire();
    private GeoJump geoJump = new GeoJump();
    private SessionTimeout sessionTimeout = new SessionTimeout();
    private ImpossibleSeq impossibleSeq = new ImpossibleSeq();
    private PathDeviation pathDeviation = new PathDeviation();

    // Session-close behavior.
    private List<String> sessionEndActions = new ArrayList<>();

    @Data
    public static class RapidFire {
        // Minimum streak size and time window used to detect bursty automated behavior.
        private int minEvents = 3;
        private int windowSeconds = 2;
    }

    @Data
    public static class GeoJump {
        // Toggle for country-switch detection inside a single session.
        private boolean enabled = true;
    }

    @Data
    public static class SessionTimeout {
        // Gap threshold after which a single logical session is treated as suspiciously stale.
        private int thresholdSeconds = 1200;
    }

    @Data
    public static class ImpossibleSeq {
        // Minimum learned transition probability below which a step is considered implausible.
        private double minProbability = 0.005;
    }

    @Data
    public static class PathDeviation {
        private double minProbability = 0.02;
    }

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
