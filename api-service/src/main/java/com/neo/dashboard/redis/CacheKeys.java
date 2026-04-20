package com.neo.dashboard.redis;

/**
 * Redis key layout shared with the Data Processor worker so both services read
 * the same snapshots (see {@code com.noveocare.dataprocessor.config.CacheKeys}).
 */
public final class CacheKeys {

    private CacheKeys() {}

    public static String sessionInsightKey(String insuredId, String sessionId) {
        return "session:insight:" + insuredId + ":" + sessionId;
    }

    public static String sessionInsightPattern() {
        return "session:insight:*";
    }

    public static String sessionInsightPattern(String insuredId) {
        return "session:insight:" + insuredId + ":*";
    }

    public static String dashboardKey(String view) {
        return "dashboard:" + view;
    }

    public static String riskKey(String insuredId) {
        return "risk:" + insuredId;
    }

    public static String nextActionsKey(String insuredId) {
        return "next_actions:" + insuredId;
    }

    public static String activeAnomalyKey(String insuredId) {
        return "anomaly:active:" + insuredId;
    }
}
