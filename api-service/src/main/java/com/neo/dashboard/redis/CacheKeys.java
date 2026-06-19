package com.neo.dashboard.redis;

/**
 * Redis key layout shared with the Data Processor worker so both services read
 * the same snapshots (see {@code com.noveocare.dataprocessor.config.CacheKeys}).
 */
public final class CacheKeys {

    private CacheKeys() {}

    public static final String V36_SCHEMA_VERSION = "v3.6.1";

    public static final String AI_RUNTIME_HEALTH_V36 = "ai:runtime:health:v3_6";
    public static final String DASHBOARD_SECURITY_OVERVIEW_V36 = "dashboard:security-overview:v3_6";
    public static final String DASHBOARD_CHURN_V36 = "dashboard:churn:v3_6";
    public static final String DASHBOARD_FORECAST_V36 = "dashboard:forecast:v3_6";
    public static final String ALERTS_LIVE_V36 = "alerts:live:v3_6";
    public static final String ALERTS_CRITICAL_V36 = "alerts:critical:v3_6";
    public static final String AI_SEQUENCE_FIELD_COVERAGE_V36 = "ai:sequence:field-coverage:v3_6";
    public static final String AI_TABULAR_FIELD_COVERAGE_V36 = "ai:tabular:field-coverage:v3_6";
    public static final String AI_MODEL_LATENCY_V36 = "ai:model-latency:v3_6";

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

    public static String sessionSequenceV36Key(String sessionId) {
        return "session:sequence:v3_6:" + sessionId;
    }

    public static String sessionScoresV36Key(String sessionId) {
        return "session:scores:v3_6:" + sessionId;
    }

    public static String sessionRiskV36Key(String insuredId, String sessionId) {
        return "session:risk:v3_6:" + insuredId + ":" + sessionId;
    }

    public static String sessionInsightPattern() {
        return "session:insight:*";
    }

    public static String sessionInsightPattern(String insuredId) {
        return "session:insight:" + insuredId + ":*";
    }

    public static String activeSessionInsightsIndexKey() {
        return "session:insight:index";
    }

    public static String activeSessionInsightsIndexKey(String insuredId) {
        return "session:insight:index:" + insuredId;
    }

    public static String trendStatsKey(String date) {
        return "stats:trend:" + date;
    }

    public static String activeAnomalyKey(String insuredId) {
        return "anomaly:active:" + insuredId;
    }

    public static String alertInvestigationKey(String eventId) {
        return "alert:investigation:" + eventId;
    }

    public static String alertLlmEvidenceKey(String eventId) {
        return "alert:llm-evidence:" + eventId;
    }

    public static String user360Key(String insuredId) {
        return "user:360:" + insuredId;
    }

    public static String userAlertsKey(String insuredId) {
        return "alerts:user:" + insuredId;
    }

    public static String explanationV36Key(String eventId, String evidenceHash, String style, String language) {
        return "ai:explanation:v3_6:alert:" + eventId + ":" + evidenceHash + ":" + style + ":" + language;
    }

    public static String explanationV36LatestKey(String eventId) {
        return "ai:explanation:v3_6:alert:" + eventId + ":latest";
    }

    public static String explanationLockKey(String eventId, String language, String style) {
        return "lock:llm-explanation:" + eventId + ":" + language + ":" + style;
    }

    public static String detectedAnomalyKey(String insuredId, String sessionId) {
        return "anomaly:detected:" + insuredId + ":" + sessionId;
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

    public static String downloadsMinuteKey(String minute) {
        return "stats:downloads:minute:" + minute;
    }

    public static String dashboardKey(String view) {
        return "dashboard:" + view;
    }

    public static String aiRuntimeHealthKey() {
        return "ai:runtime:health";
    }

    public static String aiRuntimeHealthV36Key() {
        return AI_RUNTIME_HEALTH_V36;
    }

    public static String sequenceFieldCoverageKey() {
        return "ai:sequence:field-coverage";
    }

    public static String sequenceFieldCoverageV36Key() {
        return AI_SEQUENCE_FIELD_COVERAGE_V36;
    }

    public static String tabularFieldCoverageV36Key() {
        return AI_TABULAR_FIELD_COVERAGE_V36;
    }

    public static String modelLatencyV36Key() {
        return AI_MODEL_LATENCY_V36;
    }
}
