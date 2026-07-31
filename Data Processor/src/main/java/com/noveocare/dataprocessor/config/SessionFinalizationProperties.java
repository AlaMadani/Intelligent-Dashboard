package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Session finalization parameters: inactivity timeout, maximum session duration,
 * flush intervals, late-event grace periods, end-reason labels, and field preference
 * for determining the terminal action.
 */
@Data
@ConfigurationProperties(prefix = "app.session.finalization")
public class SessionFinalizationProperties {
    /* --- Timeout and duration limits (seconds) --- */
    private long inactivityTimeoutSeconds = 1200;
    private long maxOpenDurationSeconds = 7200;
    /* --- Flush and grace periods (milliseconds / seconds) --- */
    private long expiredFlushIntervalMs = 30000;
    private long lateEventGraceSeconds = 60;
    private long gracePeriodMs = 30000;
    /* --- End-action field resolution preference --- */
    private List<String> endActionFieldPreference = List.of("action_value", "action", "frontend_action_name");
    /* --- End-reason labels --- */
    private String timeoutEndReason = "inactivity_timeout";
    private String maxDurationEndReason = "max_open_duration";
    private String explicitLogoutEndReason = "explicit_logout";
    private String explicitSsoEndReason = "explicit_sso_disconnect";
}