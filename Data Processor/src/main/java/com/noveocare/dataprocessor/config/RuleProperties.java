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
    /* --- Tier-1 static checks (hour window, login constraints, repeated failures) --- */
    private UnusualHour unusualHour = new UnusualHour();
    private SkipLogin skipLogin = new SkipLogin();
    private RepeatedFail repeatedFail = new RepeatedFail();

    /* --- Tier-1 heuristics (burst, geo-jump, timeout, impossible sequence, path deviation) --- */
    private RapidFire rapidFire = new RapidFire();
    private GeoJump geoJump = new GeoJump();
    private SessionTimeout sessionTimeout = new SessionTimeout();
    private ImpossibleSeq impossibleSeq = new ImpossibleSeq();
    private PathDeviation pathDeviation = new PathDeviation();

    /* --- Session-close behavior --- */
    private List<String> sessionEndActions = new ArrayList<>();

    /**
     * Detects bursty automated behavior based on minimum event streak and time window.
     */
    @Data
    public static class RapidFire {
        private int minEvents = 3;
        private int windowSeconds = 2;
    }

    /**
     * Toggle for country-switch detection inside a single session.
     */
    @Data
    public static class GeoJump {
        private boolean enabled = true;
    }

    /**
     * Gap threshold after which a single logical session is treated as suspiciously stale.
     */
    @Data
    public static class SessionTimeout {
        private int thresholdSeconds = 1200;
    }

    /**
     * Minimum learned transition probability below which a step is considered implausible.
     */
    @Data
    public static class ImpossibleSeq {
        private double minProbability = 0.005;
    }

    /**
     * Path deviation threshold: minimum probability below which a navigation path is flagged.
     */
    @Data
    public static class PathDeviation {
        private double minProbability = 0.02;
    }

    /**
     * Inclusive UTC hour range considered suspicious for unusual-hour detection.
     */
    @Data
    public static class UnusualHour {
        private int start;
        private int end;
    }

    /**
     * First-session actions that are acceptable without being treated as a skipped login.
     */
    @Data
    public static class SkipLogin {
        private List<String> allowedActions = new ArrayList<>();
    }

    /**
     * Event types and minimum KO streak length that trigger the repeated-failure rule.
     */
    @Data
    public static class RepeatedFail {
        private List<String> types = new ArrayList<>();
        private int consecutive;
    }
}
