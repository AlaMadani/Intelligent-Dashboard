package com.noveocare.dataprocessor.config;

/**
 * Centralizes Redis key naming so every service uses the same cache layout.
 *
 * <h2>Canonical alert Redis contract</h2>
 *
 * <h3>Canonical ZSET keys (active path)</h3>
 * <pre>
 *   alerts:live:zset:v3_6       — ZSET, member = eventId, score = event createdAt epoch millis
 *   alerts:critical:zset:v3_6   — ZSET, derived subset of live
 *   alerts:high:zset:v3_6       — ZSET, derived subset of live
 *   alerts:user:{insuredId}:zset:v3_6 — ZSET, per-insured subset
 * </pre>
 * <h3>Payload keys</h3>
 * <pre>
 *   alert:live:v3_6:{eventId}   — JSON blob of V36LiveAlertSummary
 * </pre>
 * <h3>Legacy keys (do NOT use for new code)</h3>
 * <pre>
 *   alerts:live:v3_6             — LIST (deprecated)
 *   alerts:critical:v3_6         — LIST (deprecated)
 *   alerts:user:{insuredId}      — LIST (deprecated)
 *   alerts:live:eventIds:v3_6    — SET  (deprecated, dedup)
 *   alerts:critical:eventIds:v3_6— SET  (deprecated, dedup)
 *   alerts:user:eventIds:{insuredId} — SET (deprecated, dedup)
 * </pre>
 *
 * <h3>Required invariant</h3>
 * <pre>critical ZSET ⊆ live ZSET</pre>
 *
 * <h3>TTL</h3>
 * <p>{@code RedisCacheProperties.liveStats} applied to all ZSET keys and payload keys on every write.
 *    Expiry is best-effort; the TTL is refreshed each time {@code addAlert()} is called.</p>
 *
 * <h3>Trimming</h3>
 * <p>Live ZSET is capped at 5000 members (see {@link com.noveocare.dataprocessor.service.AlertCacheService#MAX_LIVE_ALERTS}).
 *    When the cap is exceeded the oldest half is removed from <em>both</em> the live ZSET and
 *    the derived critical/high ZSETs, preserving the subset invariant.</p>
 *
 * <h3>Api-service read guidance</h3>
 * <ol>
 *   <li>Read from canonical ZSET ({@link #liveAlertsV36ZSetKey()} / {@link #criticalAlertsV36ZSetKey()}).</li>
 *   <li>Fall back to legacy LIST ({@link #liveAlertsV36Key()}) only during migration.</li>
 *   <li>Use SQL {@code anomaly_event} table as durable fallback.</li>
 * </ol>
 */
public final class CacheKeys {
    private CacheKeys() {}

    // Keys used for session-scoped intermediate state.
    public static String sessionKey(String insuredId, String sessionId) {
        return "session:" + insuredId + ":" + sessionId;
    }

    public static String sessionFullKey(String insuredId, String sessionId) {
        return "session:full:" + insuredId + ":" + sessionId;
    }

    public static String sessionRunningSummaryKey(String sessionId) {
        return "session:running-summary:v3_6:" + sessionId;
    }

    public static String nextActionsKey(String insuredId) {
        return "next_actions:" + insuredId;
    }

    public static String nextEventPredictionSessionKey(String sessionId) {
        return "next_event_prediction:session:" + sessionId;
    }

    public static String nextEventPredictionInsuredKey(String insuredId) {
        return "next_event_prediction:insured:" + insuredId;
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

public static String sequenceWindowKey(String sessionId) {
        return "session:sequence:v3_6:" + sessionId;
    }

    public static String sessionSequenceEventIdsKey(String sessionId) {
        return "session:sequence:eventIds:v3_6:" + sessionId;
    }

public static String sequenceScoresKey(String sessionId) {
        return "session:scores:v3_6:" + sessionId;
    }

    public static String sessionStateKey(String insuredId, String sessionId) {
        return "session:state:" + insuredId + ":" + sessionId;
    }

    public static String sessionStateIndexKey() {
        return "session:state:index";
    }

    public static String sessionRiskV36Key(String insuredId, String sessionId) {
        return "session:risk:v3_6:" + insuredId + ":" + sessionId;
    }

    public static String aiRuntimeHealthKey() {
        return "ai:runtime:health:v3_6";
    }

    public static String sequenceFieldCoverageKey() {
        return "ai:sequence:field-coverage:v3_6";
    }

    public static String tabularFieldCoverageKey() {
        return "ai:tabular:field-coverage:v3_6";
    }

    public static String modelLatencyKey() {
        return "ai:model-latency:v3_6";
    }

    public static String alertInvestigationKey(String eventId) {
        return "alert:investigation:" + eventId;
    }

    public static String alertLlmEvidenceKey(String eventId) {
        return "alert:llm-evidence:" + eventId;
    }

    /**
     * Legacy LIST key. Do not use as canonical source.
     * Use {@link #liveAlertsV36ZSetKey()}.
     */
    @Deprecated
    public static String liveAlertsV36Key() {
        return "alerts:live:v3_6";
    }

    /**
     * Legacy LIST key. Do not use as canonical source.
     * Use {@link #criticalAlertsV36ZSetKey()}.
     */
    @Deprecated
    public static String criticalAlertsV36Key() {
        return "alerts:critical:v3_6";
    }

    /**
     * Legacy LIST key. Do not use as canonical source.
     * Use {@link #userAlertsV36ZSetKey(String)}.
     */
    @Deprecated
    public static String userAlertsKey(String insuredId) {
        return "alerts:user:" + insuredId;
    }

    /**
     * Legacy SET key for dedup. Do not use.
     */
    @Deprecated
    public static String userAlertsEventIdsKey(String insuredId) {
        return "alerts:user:eventIds:" + insuredId;
    }

    /**
     * Legacy SET key for dedup. Do not use.
     */
    @Deprecated
    public static String liveAlertsEventIdsKey() {
        return "alerts:live:eventIds:v3_6";
    }

    /**
     * Legacy SET key for dedup. Do not use.
     */
    @Deprecated
    public static String criticalAlertsEventIdsKey() {
        return "alerts:critical:eventIds:v3_6";
    }

    public static String liveAlertsV36PayloadKey(String eventId) {
        return "alert:live:v3_6:" + eventId;
    }

    public static String liveAlertsV36ZSetKey() {
        return "alerts:live:zset:v3_6";
    }

    public static String criticalAlertsV36ZSetKey() {
        return "alerts:critical:zset:v3_6";
    }

    public static String highAlertsV36ZSetKey() {
        return "alerts:high:zset:v3_6";
    }

    public static String userAlertsV36ZSetKey(String insuredId) {
        return "alerts:user:" + insuredId + ":zset:v3_6";
    }

    public static String user360Key(String insuredId) {
        return "user:360:" + insuredId;
    }

    public static String securityOverviewDashboardKey() {
        return "dashboard:security-overview:v3_6";
    }

    public static String churnDashboardKey() {
        return "dashboard:churn:v3_6";
    }

    public static String forecastDashboardV36Key() {
        return "dashboard:forecast:v3_6";
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

    public static String detectedAnomalyKey(String insuredId, String sessionId) {
        return "anomaly:detected:" + insuredId + ":" + sessionId;
    }

    public static String sessionFirstEventKey(String sessionId) {
        return "session:first-event:v3_6:" + sessionId;
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

    public static String eventsDayKey(String date) {
        return "stats:events:day:" + date;
    }

    public static String alertsDayKey(String date) {
        return "stats:alerts:day:" + date;
    }

    public static String downloadsDayKey(String date) {
        return "stats:downloads:day:" + date;
    }
}
