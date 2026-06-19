package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.config.SessionFinalizationProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionFinalizationServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SetOperations<String, String> setOps;

    private RuleProperties ruleProperties;
    private SessionFinalizationProperties finalizationProperties;
    private com.noveocare.dataprocessor.config.RedisCacheProperties cacheProperties;
    private SessionFinalizationService service;

    @BeforeEach
    void setUp() {
        ruleProperties = new RuleProperties();
        ruleProperties.setSessionEndActions(List.of(
                "D\u00e9connexion",
                "Deconnexion",
                "D\u00c3\u00a9connexion",
                "SSO Disconnect"
        ));

        finalizationProperties = new SessionFinalizationProperties();
        cacheProperties = new com.noveocare.dataprocessor.config.RedisCacheProperties();
        cacheProperties.setSessionBuffer(java.time.Duration.ofMinutes(30));

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);

        service = new SessionFinalizationService(
                redisTemplate, objectMapper, ruleProperties, finalizationProperties, cacheProperties);
    }

    @Test
    void actionValueDeconnexionFinalizesExplicitly() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setInsuredId("ins1");
        event.setSessionId("sess1");
        event.setActionValue("D\u00e9connexion");
        event.setCreatedAt(Instant.now());

        when(valueOps.get(anyString())).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));

        service.handleIncomingEvent(event);

        boolean isEnd = service.isExplicitSessionEnd(event);
        assertThat(isEnd).isTrue();

        String reason = service.resolveEndReason(event);
        assertThat(reason).isEqualTo("explicit_logout");
    }

    @Test
    void actionValueDeconnexionWithoutAccentFinalizesExplicitly() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setInsuredId("ins1");
        event.setSessionId("sess1");
        event.setActionValue("Deconnexion");
        event.setCreatedAt(Instant.now());

        when(valueOps.get(anyString())).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));

        service.handleIncomingEvent(event);

        boolean isEnd = service.isExplicitSessionEnd(event);
        assertThat(isEnd).isTrue();
    }

    @Test
    void actionValueMojibakeDeconnexionFinalizesExplicitly() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setInsuredId("ins1");
        event.setSessionId("sess1");
        event.setActionValue("D\u00c3\u00a9connexion");
        event.setCreatedAt(Instant.now());

        when(valueOps.get(anyString())).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));

        service.handleIncomingEvent(event);

        boolean isEnd = service.isExplicitSessionEnd(event);
        assertThat(isEnd).isTrue();
    }

    @Test
    void actionValueSsoDisconnectFinalizesExplicitly() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setInsuredId("ins1");
        event.setSessionId("sess1");
        event.setActionValue("SSO Disconnect");
        event.setCreatedAt(Instant.now());

        when(valueOps.get(anyString())).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));

        service.handleIncomingEvent(event);

        boolean isEnd = service.isExplicitSessionEnd(event);
        assertThat(isEnd).isTrue();

        String reason = service.resolveEndReason(event);
        assertThat(reason).isEqualTo("explicit_sso_disconnect");
    }

    @Test
    void frontendActionNameFallbackWorksWhenActionValueMissing() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setInsuredId("ins1");
        event.setSessionId("sess1");
        event.setActionValue(null);
        event.setAction(null);
        event.setFrontendActionName("D\u00e9connexion");
        event.setCreatedAt(Instant.now());

        when(valueOps.get(anyString())).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));

        service.handleIncomingEvent(event);

        boolean isEnd = service.isExplicitSessionEnd(event);
        assertThat(isEnd).isTrue();
    }

@Test
    void sessionWithoutLogoutFinalizesAfterInactivityTimeout() {
        String insuredId = "ins1";
        String sessionId = "sess1";
        Instant now = Instant.now();
        Instant oldTime = now.minusSeconds(finalizationProperties.getInactivityTimeoutSeconds() + 10);

        com.noveocare.dataprocessor.dto.SessionState state = new com.noveocare.dataprocessor.dto.SessionState();
        state.setSessionId(sessionId);
        state.setInsuredId(insuredId);
        state.setFirstEventTimestamp(oldTime);
        state.setLastEventTimestamp(oldTime);
        state.setLastEventIngestedAt(oldTime);
        state.setEventCount(3);
        state.setEndedExplicitly(false);
        state.setFinalized(false);

        try {
            String json = objectMapper.writeValueAsString(state);
            lenient().when(valueOps.get(anyString())).thenReturn(json);
            when(setOps.members(anyString())).thenReturn(java.util.Set.of("session:state:" + insuredId + ":" + sessionId));

            List<com.noveocare.dataprocessor.dto.SessionState> openSessions = service.getOpenSessionStates();
            assertThat(openSessions).hasSize(1);

            String endReason = null;
            com.noveocare.dataprocessor.dto.SessionState openState = openSessions.get(0);
            long idleSeconds = java.time.Duration.between(openState.getLastEventIngestedAt(), Instant.now()).getSeconds();
            if (idleSeconds >= finalizationProperties.getInactivityTimeoutSeconds()) {
                endReason = finalizationProperties.getTimeoutEndReason();
            }

            assertThat(endReason).isEqualTo("inactivity_timeout");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void schedulerFinalizesExpiredSessionWithoutNewEvent() {
        String insuredId = "ins1";
        String sessionId = "sess1";
        Instant now = Instant.now();
        Instant oldTime = now.minusSeconds(finalizationProperties.getInactivityTimeoutSeconds() + 10);

        com.noveocare.dataprocessor.dto.SessionState state = new com.noveocare.dataprocessor.dto.SessionState();
        state.setSessionId(sessionId);
        state.setInsuredId(insuredId);
        state.setFirstEventTimestamp(oldTime);
        state.setLastEventTimestamp(oldTime);
        state.setLastEventIngestedAt(oldTime);
        state.setEventCount(3);
        state.setEndedExplicitly(false);
        state.setFinalized(false);

        try {
            String json = objectMapper.writeValueAsString(state);
            when(valueOps.get(anyString())).thenReturn(json).thenReturn(json);

            com.noveocare.dataprocessor.dto.SessionState finalized = service.finalizeSession(
                    insuredId, sessionId, "inactivity_timeout", false);

            assertThat(finalized).isNotNull();
            assertThat(finalized.isFinalized()).isTrue();
            assertThat(finalized.getEndReason()).isEqualTo("inactivity_timeout");
            assertThat(finalized.isEndedExplicitly()).isFalse();
            assertThat(finalized.getEndedAt()).isNotNull();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void sessionIsNotFinalizedBeforeTimeout() {
        String insuredId = "ins1";
        String sessionId = "sess1";
        Instant now = Instant.now();

        com.noveocare.dataprocessor.dto.SessionState state = new com.noveocare.dataprocessor.dto.SessionState();
        state.setSessionId(sessionId);
        state.setInsuredId(insuredId);
        state.setFirstEventTimestamp(now);
        state.setLastEventTimestamp(now);
        state.setLastEventIngestedAt(now);
        state.setEventCount(2);
        state.setFinalized(false);

        long idleSeconds = java.time.Duration.between(state.getLastEventIngestedAt(), Instant.now()).getSeconds();
        String endReason = idleSeconds >= finalizationProperties.getInactivityTimeoutSeconds()
                ? finalizationProperties.getTimeoutEndReason() : null;

        assertThat(endReason).isNull();
    }

    @Test
    void maxOpenDurationFinalizationWorks() {
        String insuredId = "ins1";
        String sessionId = "sess1";
        Instant now = Instant.now();
        Instant veryOld = now.minusSeconds(finalizationProperties.getMaxOpenDurationSeconds() + 10);

        com.noveocare.dataprocessor.dto.SessionState state = new com.noveocare.dataprocessor.dto.SessionState();
        state.setSessionId(sessionId);
        state.setInsuredId(insuredId);
        state.setFirstEventTimestamp(veryOld);
        state.setLastEventTimestamp(now.minusSeconds(100));
        state.setLastEventIngestedAt(now.minusSeconds(100));
        state.setEventCount(5);
        state.setFinalized(false);

        try {
            String json = objectMapper.writeValueAsString(state);
            when(valueOps.get(anyString())).thenReturn(json);

            com.noveocare.dataprocessor.dto.SessionState finalized = service.finalizeSession(
                    insuredId, sessionId, "max_open_duration", false);

            assertThat(finalized).isNotNull();
            assertThat(finalized.isFinalized()).isTrue();
            assertThat(finalized.getEndReason()).isEqualTo("max_open_duration");
            assertThat(finalized.isEndedExplicitly()).isFalse();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void doubleFinalizationPrevented() {
        String insuredId = "ins1";
        String sessionId = "sess1";

        com.noveocare.dataprocessor.dto.SessionState state = new com.noveocare.dataprocessor.dto.SessionState();
        state.setSessionId(sessionId);
        state.setInsuredId(insuredId);
        state.setFirstEventTimestamp(Instant.now().minusSeconds(2000));
        state.setLastEventIngestedAt(Instant.now().minusSeconds(2000));
        state.setEventCount(3);
        state.setFinalized(false);

        try {
            String json = objectMapper.writeValueAsString(state);
            com.noveocare.dataprocessor.dto.SessionState finalizedState = new com.noveocare.dataprocessor.dto.SessionState();
            finalizedState.setSessionId(state.getSessionId());
            finalizedState.setInsuredId(state.getInsuredId());
            finalizedState.setFirstEventTimestamp(state.getFirstEventTimestamp());
            finalizedState.setLastEventTimestamp(state.getLastEventTimestamp());
            finalizedState.setLastEventIngestedAt(state.getLastEventIngestedAt());
            finalizedState.setEventCount(state.getEventCount());
            finalizedState.setFinalized(true);
            finalizedState.setEndReason("inactivity_timeout");
            String finalizedJson = objectMapper.writeValueAsString(finalizedState);

            when(valueOps.get(anyString())).thenReturn(json, finalizedJson, finalizedJson, finalizedJson);
            doNothing().when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));
            lenient().when(redisTemplate.expire(anyString(), any(java.time.Duration.class))).thenReturn(true);

            service.finalizeSession(insuredId, sessionId, "inactivity_timeout", false);

            service.finalizeSession(insuredId, sessionId, "explicit_logout", true);

            com.noveocare.dataprocessor.dto.SessionState after = service.getSessionState(insuredId, sessionId);
            assertThat(after).isNotNull();
            assertThat(after.getEndReason()).isEqualTo("inactivity_timeout");
            assertThat(after.isEndedExplicitly()).isFalse();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void lateEventForFinalizedSessionIncrementsCounter() {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setInsuredId("ins1");
        event.setSessionId("sess1");
        event.setAction("someAction");
        event.setCreatedAt(Instant.now());

        com.noveocare.dataprocessor.dto.SessionState finalizedState = new com.noveocare.dataprocessor.dto.SessionState();
        finalizedState.setSessionId("sess1");
        finalizedState.setInsuredId("ins1");
        finalizedState.setFinalized(true);
        finalizedState.setEndReason("explicit_logout");
        finalizedState.setEventCount(3);

        try {
            String json = objectMapper.writeValueAsString(finalizedState);
            when(valueOps.get(anyString())).thenReturn(json);

            service.handleIncomingEvent(event);

            assertThat(service.diagnosticsSnapshot().get("lateEventsForFinalizedSessions"))
                    .isEqualTo(1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}