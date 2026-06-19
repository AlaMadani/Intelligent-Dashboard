package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionRunningSummary;
import com.noveocare.dataprocessor.dto.SessionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRunningSummaryServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisCacheProperties cacheProperties;
    private SessionRunningSummaryService service;

    @BeforeEach
    void setUp() {
        cacheProperties = new RedisCacheProperties();
        cacheProperties.setSessionBuffer(Duration.ofMinutes(30));

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn(null);

        service = new SessionRunningSummaryService(redisTemplate, objectMapper, cacheProperties);
    }

    @Test
    void testCreateEmpty() {
        SessionRunningSummary s = service.createEmpty("s1", "i1");
        assertThat(s.getSessionId()).isEqualTo("s1");
        assertThat(s.getInsuredId()).isEqualTo("i1");
        assertThat(s.getEventCount()).isZero();
        assertThat(s.getMinInterActionMs()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void testUpdateWithEventFirstEvent() {
        AuditTrailEvent e = new AuditTrailEvent();
        e.setId("e1");
        e.setSessionId("s1");
        e.setInsuredId("i1");
        e.setCreatedAt(Instant.parse("2025-01-01T10:00:00Z"));
        e.setSequenceInSession(1);
        e.setAction("Connexion");
        e.setIp("1.2.3.4");
        e.setDevice("mobile");
        e.setCountryCode("FR");
        e.setStatus("OK");
        e.setIsWeekend(0);
        e.setIsDownloadAction(0);
        e.setSessionRiskScore(10.0);

        SessionRunningSummary s = service.createEmpty("s1", "i1");
        s = service.updateWithEvent(s, e);

        assertThat(s.getEventCount()).isEqualTo(1);
        assertThat(s.getFirstTimestamp()).isEqualTo(e.getCreatedAt());
        assertThat(s.getLastTimestamp()).isEqualTo(e.getCreatedAt());
        assertThat(s.getFirstAction()).isEqualTo("Connexion");
        assertThat(s.getFirstIp()).isEqualTo("1.2.3.4");
        assertThat(s.getSuccessCount()).isEqualTo(1);
        assertThat(s.getHasLogin()).isEqualTo(1);
        assertThat(s.getHasLoggedIn()).isEqualTo(1);
        assertThat(s.getRiskScoreCount()).isEqualTo(1);
        assertThat(s.getRiskScoreMax()).isEqualTo(10.0);
    }

    @Test
    void testUpdateWithEventMultipleEvents() {
        AuditTrailEvent e1 = new AuditTrailEvent();
        e1.setId("e1");
        e1.setSessionId("s1");
        e1.setInsuredId("i1");
        e1.setCreatedAt(Instant.parse("2025-01-01T10:00:00Z"));
        e1.setSequenceInSession(1);
        e1.setAction("Connexion");
        e1.setIp("1.2.3.4");
        e1.setDevice("mobile");
        e1.setCountryCode("FR");
        e1.setStatus("OK");
        e1.setIsWeekend(0);
        e1.setIsDownloadAction(0);
        e1.setIsIpChanged(0);
        e1.setSessionRiskScore(5.0);

        AuditTrailEvent e2 = new AuditTrailEvent();
        e2.setId("e2");
        e2.setSessionId("s1");
        e2.setInsuredId("i1");
        e2.setCreatedAt(Instant.parse("2025-01-01T10:05:00Z"));
        e2.setSequenceInSession(2);
        e2.setAction("Consultation");
        e2.setIp("5.6.7.8");
        e2.setDevice("desktop");
        e2.setCountryCode("DE");
        e2.setStatus("KO");
        e2.setIsWeekend(1);
        e2.setIsDownloadAction(1);
        e2.setIsIpChanged(1);
        e2.setCumulativeKOs(1);
        e2.setLongestKoStreak(1);
        e2.setDownloadsLast2Minutes(3);
        e2.setSessionRiskScore(45.0);
        e2.setRequestDataSizeBytes(100L);
        e2.setResponseDataSizeBytes(200L);
        e2.setAnomalyType("geo_jump");
        e2.setIsAnomaly(1);
        e2.setTimeDeltaSinceLastAction(300L);

        SessionRunningSummary s = service.createEmpty("s1", "i1");
        s = service.updateWithEvent(s, e1);

        assertThat(s.getEventCount()).isEqualTo(1);
        assertThat(s.getFirstTimestamp()).isEqualTo(e1.getCreatedAt());
        assertThat(s.getLastTimestamp()).isEqualTo(e1.getCreatedAt());

        s = service.updateWithEvent(s, e2);

        assertThat(s.getEventCount()).isEqualTo(2);
        assertThat(s.getFirstTimestamp()).isEqualTo(e1.getCreatedAt());
        assertThat(s.getLastTimestamp()).isEqualTo(e2.getCreatedAt());
        assertThat(s.getFirstAction()).isEqualTo("Connexion");
        assertThat(s.getLastAction()).isEqualTo("Consultation");
        assertThat(s.getFirstIp()).isEqualTo("1.2.3.4");
        assertThat(s.getLastIp()).isEqualTo("5.6.7.8");
        assertThat(s.getSuccessCount()).isEqualTo(1);
        assertThat(s.getFailureCount()).isEqualTo(1);
        assertThat(s.getConsecutiveFailureCount()).isEqualTo(1);
        assertThat(s.getMaxConsecutiveFailureCount()).isEqualTo(1);
        assertThat(s.getWeekendCount()).isEqualTo(1);
        assertThat(s.getIpChangedDetected()).isEqualTo(1);
        assertThat(s.getTotalDownloadActions()).isEqualTo(1);
        assertThat(s.getMaxDownloadsIn2Minutes()).isEqualTo(3);
        assertThat(s.getTotalRequestBytes()).isEqualTo(100L);
        assertThat(s.getTotalResponseBytes()).isEqualTo(200L);
        assertThat(s.getAnomalyEventCount()).isEqualTo(1);
        assertThat(s.getTotalInterActionSeconds()).isEqualTo(300L);
        assertThat(s.getInterActionCount()).isEqualTo(1);
        assertThat(s.getRiskScoreCount()).isEqualTo(2);
        assertThat(s.getRiskScoreMax()).isEqualTo(45.0);
    }

    @Test
    void testUpdateWithEventOutOfOrder() {
        AuditTrailEvent lateEvent = new AuditTrailEvent();
        lateEvent.setId("e0");
        lateEvent.setSessionId("s1");
        lateEvent.setInsuredId("i1");
        lateEvent.setCreatedAt(Instant.parse("2025-01-01T09:00:00Z"));
        lateEvent.setSequenceInSession(0);
        lateEvent.setAction("OldAction");
        lateEvent.setIp("0.0.0.0");
        lateEvent.setStatus("OK");
        lateEvent.setIsIpChanged(0);
        lateEvent.setSessionRiskScore(0.0);

        AuditTrailEvent mainEvent = new AuditTrailEvent();
        mainEvent.setId("e1");
        mainEvent.setSessionId("s1");
        mainEvent.setInsuredId("i1");
        mainEvent.setCreatedAt(Instant.parse("2025-01-01T10:00:00Z"));
        mainEvent.setSequenceInSession(1);
        mainEvent.setAction("Connexion");
        mainEvent.setIp("1.2.3.4");
        mainEvent.setCountryCode("FR");
        mainEvent.setStatus("OK");
        mainEvent.setIsIpChanged(0);
        mainEvent.setSessionRiskScore(5.0);

        SessionRunningSummary s = service.createEmpty("s1", "i1");
        s = service.updateWithEvent(s, mainEvent);

        assertThat(s.getFirstTimestamp()).isEqualTo(mainEvent.getCreatedAt());
        assertThat(s.getFirstAction()).isEqualTo("Connexion");
        assertThat(s.getEventCount()).isEqualTo(1);

        s = service.updateWithEvent(s, lateEvent);

        assertThat(s.getEventCount()).isEqualTo(2);
        assertThat(s.getFirstTimestamp()).isEqualTo(lateEvent.getCreatedAt());
        assertThat(s.getFirstAction()).isEqualTo("OldAction");
        assertThat(s.getLastTimestamp()).isEqualTo(mainEvent.getCreatedAt());
        assertThat(s.getLastAction()).isEqualTo("Connexion");
    }

    @Test
    void testToLiveSessionSummary() {
        AuditTrailEvent e1 = new AuditTrailEvent();
        e1.setId("e1");
        e1.setSessionId("s1");
        e1.setInsuredId("i1");
        e1.setCreatedAt(Instant.parse("2025-01-01T10:00:00Z"));
        e1.setSequenceInSession(1);
        e1.setAction("Connexion");
        e1.setIp("1.2.3.4");
        e1.setDevice("mobile");
        e1.setCountryCode("FR");
        e1.setStatus("OK");
        e1.setIsWeekend(0);
        e1.setIsDownloadAction(0);
        e1.setIsIpChanged(0);
        e1.setSessionRiskScore(5.0);

        AuditTrailEvent e2 = new AuditTrailEvent();
        e2.setId("e2");
        e2.setSessionId("s1");
        e2.setInsuredId("i1");
        e2.setCreatedAt(Instant.parse("2025-01-01T10:05:00Z"));
        e2.setSequenceInSession(2);
        e2.setAction("Deconnexion");
        e2.setRoute("/logout");
        e2.setIp("1.2.3.4");
        e2.setDevice("mobile");
        e2.setCountryCode("FR");
        e2.setStatus("OK");
        e2.setIsWeekend(0);
        e2.setIsDownloadAction(0);
        e2.setIsIpChanged(0);
        e2.setTimeDeltaSinceLastAction(300L);
        e2.setSessionRiskScore(5.0);

        SessionRunningSummary s = service.createEmpty("s1", "i1");
        s = service.updateWithEvent(s, e1);
        s = service.updateWithEvent(s, e2);

        SessionSummary summary = service.toLiveSessionSummary(s, List.of());
        assertThat(summary.getTotalEvents()).isEqualTo(2);
        assertThat(summary.getFirstAction()).isEqualTo("Connexion");
        assertThat(summary.getLastAction()).isEqualTo("Deconnexion");
        assertThat(summary.getHasLogin()).isEqualTo(1);
        assertThat(summary.getHasLogout()).isEqualTo(1);
        assertThat(summary.getTotalOKs()).isEqualTo(2);
    }

    @Test
    void testToLiveSessionSummaryWithRecentEvents() {
        AuditTrailEvent e1 = new AuditTrailEvent();
        e1.setId("e1");
        e1.setSessionId("s1");
        e1.setInsuredId("i1");
        e1.setCreatedAt(Instant.parse("2025-01-01T10:00:00Z"));
        e1.setSequenceInSession(1);
        e1.setAction("Connexion");
        e1.setRoute("/login");
        e1.setIp("1.2.3.4");
        e1.setCountryCode("FR");
        e1.setStatus("OK");
        e1.setIsIpChanged(0);
        e1.setSessionRiskScore(5.0);

        AuditTrailEvent e2 = new AuditTrailEvent();
        e2.setId("e2");
        e2.setSessionId("s1");
        e2.setInsuredId("i1");
        e2.setCreatedAt(Instant.parse("2025-01-01T10:05:00Z"));
        e2.setSequenceInSession(2);
        e2.setAction("Consultation");
        e2.setRoute("/documents");
        e2.setIp("1.2.3.4");
        e2.setCountryCode("FR");
        e2.setStatus("OK");
        e2.setIsIpChanged(0);
        e2.setAnomalyType("normal");
        e2.setSessionRiskScore(5.0);

        SessionRunningSummary s = service.createEmpty("s1", "i1");
        s = service.updateWithEvent(s, e1);
        s = service.updateWithEvent(s, e2);

        SessionSummary summary = service.toLiveSessionSummary(s, List.of(e1, e2));
        assertThat(summary.getTotalEvents()).isEqualTo(2);
        assertThat(summary.getActionSequence()).containsExactly("Connexion", "Consultation");
        assertThat(summary.getActionSequenceSignature()).isEqualTo("Connexion > Consultation");
        assertThat(summary.getUniqueActions()).isEqualTo(2);
    }

    @Test
    void testEventCountMatches() {
        SessionRunningSummary s = service.createEmpty("s1", "i1");
        for (int i = 1; i <= 100; i++) {
            AuditTrailEvent e = new AuditTrailEvent();
            e.setId("e" + i);
            e.setSessionId("s1");
            e.setInsuredId("i1");
            e.setCreatedAt(Instant.parse("2025-01-01T10:00:00Z").plusSeconds(i * 10));
            e.setSequenceInSession(i);
            e.setAction("action" + i);
            e.setIp("1.2.3.4");
            e.setStatus(i % 2 == 0 ? "KO" : "OK");
            e.setIsIpChanged(0);
            e.setIsDownloadAction(0);
            e.setSessionRiskScore((double) i);
            s = service.updateWithEvent(s, e);
        }

        assertThat(s.getEventCount()).isEqualTo(100);
        assertThat(s.getSuccessCount()).isEqualTo(50);
        assertThat(s.getFailureCount()).isEqualTo(50);
        assertThat(s.getRiskScoreCount()).isEqualTo(100);
        assertThat(s.getFirstSequenceInSession()).isEqualTo(1);
        assertThat(s.getLastSequenceInSession()).isEqualTo(100);
    }

    @Test
    void testConsecutiveFailures() {
        SessionRunningSummary s = service.createEmpty("s1", "i1");

        for (int i = 1; i <= 5; i++) {
            AuditTrailEvent e = new AuditTrailEvent();
            e.setId("e" + i);
            e.setSessionId("s1");
            e.setInsuredId("i1");
            e.setCreatedAt(Instant.parse("2025-01-01T10:00:00Z").plusSeconds(i * 10));
            e.setSequenceInSession(i);
            e.setAction("action");
            e.setStatus("KO");
            e.setSessionRiskScore(0.0);
            s = service.updateWithEvent(s, e);
        }

        assertThat(s.getConsecutiveFailureCount()).isEqualTo(5);
        assertThat(s.getMaxConsecutiveFailureCount()).isEqualTo(5);

        AuditTrailEvent ok = new AuditTrailEvent();
        ok.setId("e6");
        ok.setSessionId("s1");
        ok.setInsuredId("i1");
        ok.setCreatedAt(Instant.parse("2025-01-01T10:01:00Z"));
        ok.setSequenceInSession(6);
        ok.setAction("action");
        ok.setStatus("OK");
        ok.setSessionRiskScore(0.0);
        s = service.updateWithEvent(s, ok);

        assertThat(s.getConsecutiveFailureCount()).isZero();
    }
}