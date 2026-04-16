package com.noveocare.dataprocessor.config;

/**
 * Centralizes Redis key naming so every service uses the same cache layout.
 */
public final class CacheKeys {
    private CacheKeys() {}

    // Keys used for session-scoped intermediate state.
    public static String sessionKey(String insuredId, String sessionId) {
        return "session:" + insuredId + ":" + sessionId;
    }

    public static String nextActionsKey(String insuredId) {
        return "next_actions:" + insuredId;
    }

    public static String riskKey(String insuredId) {
        return "risk:" + insuredId;
    }

    public static String liveStatsKey(String date) {
        return "stats:live:" + date;
    }

    public static String sessionAnalysisKey(String insuredId, String sessionId) {
        return "session:analysis:" + insuredId + ":" + sessionId;
    }

    public static String sessionInsightKey(String insuredId, String sessionId) {
        return "session:insight:" + insuredId + ":" + sessionId;
    }

    public static String trendStatsKey(String date) {
        return "stats:trend:" + date;
    }

    public static String activeAnomalyKey(String insuredId) {
        return "anomaly:active:" + insuredId;
    }

    public static String detectedAnomalyKey(String insuredId, String sessionId) {
        return "anomaly:detected:" + insuredId + ":" + sessionId;
    }

    public static String pendingAlertsKey(String insuredId, String sessionId) {
        return "alerts:pending:" + insuredId + ":" + sessionId;
    }

    // Keys used for rolling minute-based live statistics.
    public static String eventsMinuteKey(String minute) {
        return "stats:events:minute:" + minute;
    }

    public static String alertsMinuteKey(String minute) {
        return "stats:alerts:minute:" + minute;
    }

    public static String actionsMinuteKey(String minute) {
        return "stats:actions:minute:" + minute;
    }

    public static String countriesMinuteKey(String minute) {
        return "stats:countries:minute:" + minute;
    }

    public static String koMinuteKey(String minute) {
        return "stats:ko:minute:" + minute;
    }

    public static String dailyActionCountsKey(String date) {
        return "stats:daily:" + date;
    }

    public static String dashboardKey(String view) {
        return "dashboard:" + view;
    }
}
