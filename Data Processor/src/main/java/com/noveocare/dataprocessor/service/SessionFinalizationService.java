package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.ai.TextNormalization;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.config.SessionFinalizationProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class SessionFinalizationService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RuleProperties ruleProperties;
    private final SessionFinalizationProperties finalizationProperties;
    private final RedisCacheProperties cacheProperties;

    private final AtomicLong sessionsFinalizedByExplicitEnd = new AtomicLong();
    private final AtomicLong sessionsFinalizedByInactivityTimeout = new AtomicLong();
    private final AtomicLong sessionsFinalizedByMaxDuration = new AtomicLong();
    private final AtomicLong lateEventsForFinalizedSessions = new AtomicLong();
    private final AtomicReference<String> lastLateEventSessionId = new AtomicReference<>();
    private final AtomicReference<String> lastLateEventAction = new AtomicReference<>();
    private final AtomicReference<Instant> lastLateEventAt = new AtomicReference<>();
    private volatile Instant expiredSessionFlushLastRunAt;
    private volatile int expiredSessionFlushLastFinalizedCount;

    public void handleIncomingEvent(AuditTrailEvent event) {
        String key = stateKey(event.getInsuredId(), event.getSessionId());
        SessionState state = readState(key);
        if (state == null) {
            state = new SessionState();
            state.setSessionId(event.getSessionId());
            state.setInsuredId(event.getInsuredId());
            state.setFirstEventTimestamp(event.getCreatedAt() != null ? event.getCreatedAt() : Instant.now());
            state.setLastEventTimestamp(event.getCreatedAt() != null ? event.getCreatedAt() : Instant.now());
            state.setLastEventIngestedAt(Instant.now());
            state.setEventCount(1);
            state.setEndedExplicitly(false);
            state.setFinalized(false);
            saveState(key, state);
            addToIndex(key);
            return;
        }
        if (state.isFinalized()) {
            lateEventsForFinalizedSessions.incrementAndGet();
            String lateAction = resolveEventAction(event);
            lastLateEventSessionId.set(event.getSessionId());
            lastLateEventAction.set(lateAction);
            lastLateEventAt.set(Instant.now());
            log.warn("Late event for finalized session {}/{}, action={}",
                    event.getInsuredId(), event.getSessionId(), lateAction);
            return;
        }
        state.setLastEventTimestamp(event.getCreatedAt() != null ? event.getCreatedAt() : Instant.now());
        state.setLastEventIngestedAt(Instant.now());
        state.setEventCount(state.getEventCount() + 1);
        if (state.getFirstEventTimestamp() == null) {
            state.setFirstEventTimestamp(event.getCreatedAt() != null ? event.getCreatedAt() : Instant.now());
        }
        saveState(key, state);
        addToIndex(key);
    }

    public boolean isExplicitSessionEnd(AuditTrailEvent event) {
        String value = findEndActionField(event);
        if (value == null || value.isBlank()) {
            return false;
        }
        return ruleProperties.getSessionEndActions().stream()
                .anyMatch(action -> TextNormalization.equalsNormalized(action, value));
    }

    public String resolveEndReason(AuditTrailEvent event) {
        String value = findEndActionField(event);
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = TextNormalization.comparisonKey(value);
        String ssoKey = TextNormalization.comparisonKey("SSO Disconnect");
        if (normalized.contains(ssoKey) || normalized.equals(ssoKey)) {
            return finalizationProperties.getExplicitSsoEndReason();
        }
        return finalizationProperties.getExplicitLogoutEndReason();
    }

    public SessionState finalizeSession(String insuredId, String sessionId, String endReason, boolean endedExplicitly) {
        String key = stateKey(insuredId, sessionId);
        SessionState state = readState(key);
        if (state == null || state.isFinalized()) {
            return state;
        }
        state.setFinalized(true);
        state.setEndReason(endReason);
        state.setEndedExplicitly(endedExplicitly);
        state.setEndedAt(Instant.now());
        saveState(key, state);
        removeFromIndex(key);
        updateFinalizationCounter(endReason);
        Duration graceTtl = Duration.ofSeconds(finalizationProperties.getLateEventGraceSeconds());
        Duration ttl = graceTtl.compareTo(cacheProperties.getSessionBuffer()) < 0
                ? graceTtl : cacheProperties.getSessionBuffer();
        redisTemplate.expire(key, ttl);
        return state;
    }

    public boolean isSessionFinalized(String insuredId, String sessionId) {
        SessionState state = readState(stateKey(insuredId, sessionId));
        return state != null && state.isFinalized();
    }

    public List<SessionState> getOpenSessionStates() {
        Set<String> keys = redisTemplate.opsForSet().members(CacheKeys.sessionStateIndexKey());
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<SessionState> states = new ArrayList<>();
        for (String key : keys) {
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json == null) {
                    redisTemplate.opsForSet().remove(CacheKeys.sessionStateIndexKey(), key);
                    continue;
                }
                SessionState state = objectMapper.readValue(json, SessionState.class);
                if (!state.isFinalized()) {
                    states.add(state);
                }
            } catch (Exception e) {
                log.warn("Failed to deserialize session state for key {}", key, e);
                redisTemplate.opsForSet().remove(CacheKeys.sessionStateIndexKey(), key);
            }
        }
        return states;
    }

    public SessionState getSessionState(String insuredId, String sessionId) {
        return readState(stateKey(insuredId, sessionId));
    }

    public int openSessionCount() {
        Set<String> keys = redisTemplate.opsForSet().members(CacheKeys.sessionStateIndexKey());
        if (keys == null || keys.isEmpty()) return 0;
        int count = 0;
        for (String key : keys) {
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json != null) {
                    SessionState state = objectMapper.readValue(json, SessionState.class);
                    if (!state.isFinalized()) count++;
                }
            } catch (Exception e) {
                // skip
            }
        }
        return count;
    }

    public void markFlushRun(int finalizedCount) {
        this.expiredSessionFlushLastRunAt = Instant.now();
        this.expiredSessionFlushLastFinalizedCount = finalizedCount;
    }

    // --- Diagnostics snapshots ---

    public Map<String, Object> diagnosticsSnapshot() {
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("openSessionCount", openSessionCount());
        diag.put("sessionsFinalizedByExplicitEnd", sessionsFinalizedByExplicitEnd.get());
        diag.put("sessionsFinalizedByInactivityTimeout", sessionsFinalizedByInactivityTimeout.get());
        diag.put("sessionsFinalizedByMaxDuration", sessionsFinalizedByMaxDuration.get());
        diag.put("expiredSessionFlushLastRunAt", expiredSessionFlushLastRunAt == null ? null : expiredSessionFlushLastRunAt.toString());
        diag.put("expiredSessionFlushLastFinalizedCount", expiredSessionFlushLastFinalizedCount);
        diag.put("lateEventsForFinalizedSessions", lateEventsForFinalizedSessions.get());
        diag.put("lastLateEventSessionId", lastLateEventSessionId.get());
        diag.put("lastLateEventAction", lastLateEventAction.get());
        diag.put("lastLateEventAt", lastLateEventAt.get() != null ? lastLateEventAt.get().toString() : null);
        diag.put("finalizationGracePeriodMs", finalizationProperties.getGracePeriodMs());
        return diag;
    }

    private String resolveEventAction(AuditTrailEvent event) {
        if (event == null) return "unknown";
        if (event.getActionValue() != null && !event.getActionValue().isBlank()) {
            return event.getActionValue();
        }
        if (event.getFrontendActionName() != null && !event.getFrontendActionName().isBlank()) {
            return event.getFrontendActionName();
        }
        if (event.getAction() != null && !event.getAction().isBlank()) {
            return event.getAction();
        }
        return "unknown";
    }

    // --- Private helpers ---

    private String stateKey(String insuredId, String sessionId) {
        return CacheKeys.sessionStateKey(insuredId, sessionId);
    }

    private SessionState readState(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, SessionState.class);
        } catch (Exception e) {
            log.warn("Failed to read session state for key {}", key, e);
            return null;
        }
    }

    private void saveState(String key, SessionState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, cacheProperties.getSessionBuffer());
        } catch (Exception e) {
            log.error("Failed to save session state for key {}", key, e);
        }
    }

    private void addToIndex(String key) {
        redisTemplate.opsForSet().add(CacheKeys.sessionStateIndexKey(), key);
    }

    private void removeFromIndex(String key) {
        redisTemplate.opsForSet().remove(CacheKeys.sessionStateIndexKey(), key);
    }

    private String findEndActionField(AuditTrailEvent event) {
        for (String field : finalizationProperties.getEndActionFieldPreference()) {
            String value = switch (field) {
                case "action_value" -> event.getActionValue();
                case "action" -> event.getAction();
                case "frontend_action_name" -> event.getFrontendActionName();
                default -> null;
            };
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void updateFinalizationCounter(String endReason) {
        if (endReason == null) return;
        switch (endReason) {
            case "explicit_logout", "explicit_sso_disconnect" -> sessionsFinalizedByExplicitEnd.incrementAndGet();
            case "inactivity_timeout" -> sessionsFinalizedByInactivityTimeout.incrementAndGet();
            case "max_open_duration" -> sessionsFinalizedByMaxDuration.incrementAndGet();
        }
    }
}