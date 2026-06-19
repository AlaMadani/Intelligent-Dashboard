package com.noveocare.dataprocessor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.session.finalization")
public class SessionFinalizationProperties {

    private long inactivityTimeoutSeconds = 1200;
    private long maxOpenDurationSeconds = 7200;
    private long expiredFlushIntervalMs = 30000;
    private long lateEventGraceSeconds = 60;
    private long gracePeriodMs = 30000;
    private List<String> endActionFieldPreference = List.of("action_value", "action", "frontend_action_name");
    private String timeoutEndReason = "inactivity_timeout";
    private String maxDurationEndReason = "max_open_duration";
    private String explicitLogoutEndReason = "explicit_logout";
    private String explicitSsoEndReason = "explicit_sso_disconnect";
}