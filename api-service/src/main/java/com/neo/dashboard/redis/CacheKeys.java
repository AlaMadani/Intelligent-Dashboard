package com.neo.dashboard.redis;

/**
 * Redis key layout shared with the Data Processor worker so both services read
 * the same snapshots (see {@code com.noveocare.dataprocessor.config.CacheKeys}).
 */
public final class CacheKeys {

    private CacheKeys() {}

    /** The schema version suffix used across all cache keys ({@code v3.6.1}). */
    public static final String V36_SCHEMA_VERSION = "v3.6.1";

    /** Redis key storing AI runtime health data for v3.6. */
    public static final String AI_RUNTIME_HEALTH_V36 = "ai:runtime:health:v3_6";
    /** Redis key storing the security-overview dashboard snapshot. */
    public static final String DASHBOARD_SECURITY_OVERVIEW_V36 = "dashboard:security-overview:v3_6";
    /** Redis key storing the churn-analysis dashboard snapshot. */
    public static final String DASHBOARD_CHURN_V36 = "dashboard:churn:v3_6";
    /** Redis key storing the forecast dashboard snapshot. */
    public static final String DASHBOARD_FORECAST_V36 = "dashboard:forecast:v3_6";
    /** @deprecated Legacy LIST key — use {@link #ALERTS_LIVE_V36_ZSET} */
    @Deprecated
    public static final String ALERTS_LIVE_V36 = "alerts:live:v3_6";
    /** @deprecated Legacy LIST key — use {@link #ALERTS_CRITICAL_V36_ZSET} */
    @Deprecated
    public static final String ALERTS_CRITICAL_V36 = "alerts:critical:v3_6";

    /** Canonical ZSET keys (dataprocessor primary). */
    public static final String ALERTS_LIVE_V36_ZSET = "alerts:live:zset:v3_6";
    public static final String ALERTS_CRITICAL_V36_ZSET = "alerts:critical:zset:v3_6";
    public static final String ALERTS_HIGH_V36_ZSET = "alerts:high:zset:v3_6";

    /** Prefix for per-alert JSON payloads stored by canonical ZSET member eventId. */
    public static final String ALERT_LIVE_V36_PAYLOAD_PREFIX = "alert:live:v3_6:";

    /** Builds the full Redis key for a live-alert payload by event ID. */
    public static String liveAlertPayloadKey(String eventId) {
        return ALERT_LIVE_V36_PAYLOAD_PREFIX + eventId;
    }

    /** Builds the ZSET key that stores alert IDs for a given insured user. */
    public static String userAlertsZSetKey(String insuredId) {
        return "alerts:user:" + insuredId + ":zset:v3_6";
    }
    /** Redis key storing AI sequence-level field-coverage metrics. */
    public static final String AI_SEQUENCE_FIELD_COVERAGE_V36 = "ai:sequence:field-coverage:v3_6";
    /** Redis key storing AI tabular/column-level field-coverage metrics. */
    public static final String AI_TABULAR_FIELD_COVERAGE_V36 = "ai:tabular:field-coverage:v3_6";
    /** Redis key storing AI model latency statistics. */
    public static final String AI_MODEL_LATENCY_V36 = "ai:model-latency:v3_6";

    /** Builds the key for session-scoped intermediate state. */
    public static String sessionKey(String insuredId, String sessionId) {
        return "session:" + insuredId + ":" + sessionId;
    }

    /** Builds the key storing next-action recommendations for an insured user. */
    public static String nextActionsKey(String insuredId) {
        return "next_actions:" + insuredId;
    }

    /** Builds the key storing risk-assessment data for an insured user. */
    public static String riskKey(String insuredId) {
        return "risk:" + insuredId;
    }

    /** Builds the key for live statistics aggregated by date. */
    public static String liveStatsKey(String date) {
        return "stats:live:" + date;
    }

    /** Builds the key for session analysis data. */
    public static String sessionAnalysisKey(String insuredId, String sessionId) {
        return "session:analysis:" + insuredId + ":" + sessionId;
    }

    /** Builds the key for session insight data. */
    public static String sessionInsightKey(String insuredId, String sessionId) {
        return "session:insight:" + insuredId + ":" + sessionId;
    }

    /** Builds the key for the v3.6 session sequence data. */
    public static String sessionSequenceV36Key(String sessionId) {
        return "session:sequence:v3_6:" + sessionId;
    }

    /** Builds the key for the v3.6 session scores data. */
    public static String sessionScoresV36Key(String sessionId) {
        return "session:scores:v3_6:" + sessionId;
    }

    /** Builds the key for v3.6 session risk data. */
    public static String sessionRiskV36Key(String insuredId, String sessionId) {
        return "session:risk:v3_6:" + insuredId + ":" + sessionId;
    }

    /** Glob pattern matching all session:insight keys (across all users). */
    public static String sessionInsightPattern() {
        return "session:insight:*";
    }

    /** Glob pattern matching session:insight keys for a specific insured user. */
    public static String sessionInsightPattern(String insuredId) {
        return "session:insight:" + insuredId + ":*";
    }

    /** Key of the global active-session-insights index set. */
    public static String activeSessionInsightsIndexKey() {
        return "session:insight:index";
    }

    /** Key of the per-insured active-session-insights index set. */
    public static String activeSessionInsightsIndexKey(String insuredId) {
        return "session:insight:index:" + insuredId;
    }

    /** Builds the key for trend statistics aggregated by date. */
    public static String trendStatsKey(String date) {
        return "stats:trend:" + date;
    }

    /** Builds the key marking an insured user as having an active anomaly. */
    public static String activeAnomalyKey(String insuredId) {
        return "anomaly:active:" + insuredId;
    }

    /** Builds the key storing investigation metadata for a given alert event. */
    public static String alertInvestigationKey(String eventId) {
        return "alert:investigation:" + eventId;
    }

    /** Builds the key storing LLM-generated evidence text for a given alert event. */
    public static String alertLlmEvidenceKey(String eventId) {
        return "alert:llm-evidence:" + eventId;
    }

    /** Builds the key for the insured user's 360° aggregated view. */
    public static String user360Key(String insuredId) {
        return "user:360:" + insuredId;
    }

    /**
     * Legacy LIST key — use {@link #userAlertsZSetKey(String)}.
     * @deprecated Canonical ZSET key is alerts:user:{insuredId}:zset:v3_6
     */
    @Deprecated
    public static String userAlertsKey(String insuredId) {
        return "alerts:user:" + insuredId;
    }

    /** Builds a fully-qualified key for a cached LLM explanation by event, evidence hash, style, and language. */
    public static String explanationV36Key(String eventId, String evidenceHash, String style, String language) {
        return "ai:explanation:v3_6:alert:" + eventId + ":" + evidenceHash + ":" + style + ":" + language;
    }

    /** Builds the key that points to the latest explanation for a given alert event. */
    public static String explanationV36LatestKey(String eventId) {
        return "ai:explanation:v3_6:alert:" + eventId + ":latest";
    }

    /** Builds a distributed-lock key used to prevent concurrent LLM explanation generation. */
    public static String explanationLockKey(String eventId, String language, String style) {
        return "lock:llm-explanation:" + eventId + ":" + language + ":" + style;
    }

    /** Builds the key for next-event prediction results scoped to a session. */
    public static String nextEventPredictionSessionKey(String sessionId) {
        return "next_event_prediction:session:" + sessionId;
    }

    /** Builds the key for next-event prediction results scoped to an insured user. */
    public static String nextEventPredictionInsuredKey(String insuredId) {
        return "next_event_prediction:insured:" + insuredId;
    }

    /** Builds the key for detected anomaly data scoped to an insured user and session. */
    public static String detectedAnomalyKey(String insuredId, String sessionId) {
        return "anomaly:detected:" + insuredId + ":" + sessionId;
    }

    /** Builds the key for event-count statistics within a given minute. */
    public static String eventsMinuteKey(String minute) {
        return "stats:events:minute:" + minute;
    }

    /** Builds the key for alert-count statistics within a given minute. */
    public static String alertsMinuteKey(String minute) {
        return "stats:alerts:minute:" + minute;
    }

    /** Builds the key for action-count statistics within a given minute. */
    public static String actionsMinuteKey(String minute) {
        return "stats:actions:minute:" + minute;
    }

    /** Builds the key for country-coverage statistics within a given minute. */
    public static String countriesMinuteKey(String minute) {
        return "stats:countries:minute:" + minute;
    }

    /** Builds the key for knock-out (failure) count statistics within a given minute. */
    public static String koMinuteKey(String minute) {
        return "stats:ko:minute:" + minute;
    }

    /** Builds the key for download-count statistics within a given minute. */
    public static String downloadsMinuteKey(String minute) {
        return "stats:downloads:minute:" + minute;
    }

    /** Builds the key for a generic dashboard view by name. */
    public static String dashboardKey(String view) {
        return "dashboard:" + view;
    }

    /** Key for the (non-versioned) AI runtime health data. */
    public static String aiRuntimeHealthKey() {
        return "ai:runtime:health";
    }

    /** Key for the versioned (v3.6) AI runtime health data (delegates to {@link #AI_RUNTIME_HEALTH_V36}). */
    public static String aiRuntimeHealthV36Key() {
        return AI_RUNTIME_HEALTH_V36;
    }

    /** Key for the (non-versioned) AI sequence field-coverage data. */
    public static String sequenceFieldCoverageKey() {
        return "ai:sequence:field-coverage";
    }

    /** Key for the versioned (v3.6) AI sequence field-coverage data. */
    public static String sequenceFieldCoverageV36Key() {
        return AI_SEQUENCE_FIELD_COVERAGE_V36;
    }

    /** Key for the versioned (v3.6) AI tabular field-coverage data. */
    public static String tabularFieldCoverageV36Key() {
        return AI_TABULAR_FIELD_COVERAGE_V36;
    }

    /** Key for the versioned (v3.6) AI model latency data. */
    public static String modelLatencyV36Key() {
        return AI_MODEL_LATENCY_V36;
    }
}
