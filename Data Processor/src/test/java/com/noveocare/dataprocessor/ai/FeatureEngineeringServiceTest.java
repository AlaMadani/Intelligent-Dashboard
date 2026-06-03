package com.noveocare.dataprocessor.ai;

import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureEngineeringServiceTest {

    private FeatureEngineeringService featureEngineeringService;

    @BeforeEach
    void setUp() {
        FeatureEngineeringProperties featureProperties = new FeatureEngineeringProperties();
        featureProperties.setDownloadWindowSeconds(120);
        featureProperties.setRapidActionSeconds(7);
        featureProperties.setSessionAlertRiskThreshold(60.0);
        featureEngineeringService = new FeatureEngineeringService(featureProperties);
    }

    @Test
    void enrichesEventsWithSessionFields() {
        Instant base = Instant.parse("2025-01-01T03:00:00Z");
        AuditTrailEvent first = event("Connexion", "OK", base, 1, "1.1.1.1", "WEB");
        AuditTrailEvent second = event("Ouverture d'un document contractuel", "KO", base.plusSeconds(5), 2, "1.1.1.1", "WEB");
        AuditTrailEvent third = event("Connexion", "OK", base.plusSeconds(9), 3, "2.2.2.2", "MOBILE_ANDROID");

        List<AuditTrailEvent> enriched = featureEngineeringService.enrichSessionEvents(List.of(first, second, third));

        assertEquals("", enriched.get(0).getPrevAction());
        assertEquals("Connexion", enriched.get(1).getPrevAction());
        assertEquals(5L, enriched.get(1).getTimeDeltaSinceLastAction());
        assertEquals(1, enriched.get(1).getIsDownloadAction());
        assertEquals(1, enriched.get(1).getDownloadActionsInSession());
        assertEquals(1, enriched.get(2).getIsIpChanged());
        assertEquals(1, enriched.get(2).getIsDeviceChanged());
        assertTrue(enriched.get(1).getSessionRiskScore() >= 40.0);
    }

    @Test
    void buildsSessionSummaryFromRawDatasetAlignedFields() {
        Instant base = Instant.parse("2025-01-01T10:00:00Z");
        AuditTrailEvent first = rawEvent("login_page", "Connexion", "OK", base, 1);
        first.setPersona("self_service");
        first.setIpCountry("FR");
        first.setCity("Paris");
        first.setSessionNumber(7);

        AuditTrailEvent second = rawEvent("documents", "Envoi d'un document", "OK", base.plusSeconds(60), 2);
        second.setPersona("self_service");
        second.setIpCountry("FR");
        second.setCity("Paris");
        second.setSessionNumber(7);
        second.setAnomalyType("data_exfiltration");
        second.setIsAnomaly(1);

        List<AuditTrailEvent> enriched = featureEngineeringService.enrichSessionEvents(List.of(first, second));
        SessionSummary summary = featureEngineeringService.buildSessionSummary(enriched);

        assertEquals(2, summary.getTotalEvents());
        assertEquals(1, summary.getTotalDownloadActions());
        assertEquals(1, summary.getHasLogin());
        assertEquals("data_exfiltration", summary.getPrimaryAnomalyType());
        assertEquals("Connexion", summary.getFirstAction());
        assertEquals("Envoi d'un document", summary.getLastAction());
        assertEquals("login_page", summary.getFirstRoute());
        assertEquals("documents", summary.getLastRoute());
        assertEquals(List.of("Connexion", "Envoi d'un document"), summary.getActionSequence());
        assertEquals(Map.of("Connexion", 1L, "Envoi d'un document", 1L), summary.getActionCounts());
    }

    @Test
    void repairsMojibakeBeforeBuildingSummary() {
        Instant base = Instant.parse("2025-01-01T10:00:00Z");
        AuditTrailEvent first = event("Connexion", "OK", base, 1, "1.1.1.1", "WEB");
        AuditTrailEvent second = event(doubleMojibake("Déconnexion"), "OK", base.plusSeconds(60), 2, "1.1.1.1", "WEB");

        SessionSummary summary = featureEngineeringService.buildSessionSummary(
                featureEngineeringService.enrichSessionEvents(List.of(first, second)));

        assertEquals("Déconnexion", summary.getLastAction());
        assertEquals(1, summary.getHasLogout());
    }

    private AuditTrailEvent event(String action, String status, Instant createdAt, int sequence, String ip, String device) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setInsuredId("insured-1");
        event.setSessionId("session-1");
        event.setAction(action);
        event.setStatus(status);
        event.setCreatedAt(createdAt);
        event.setSequenceInSession(sequence);
        event.setSessionLength(3);
        event.setIp(ip);
        event.setDevice(device);
        event.setType("LOGGING_ACTIONS");
        event.setCountryCode("FR");
        event.setCity("Paris");
        event.setMonth("2025-01");
        return event;
    }

    private AuditTrailEvent rawEvent(String page, String actionValue, String status, Instant createdAt, int sequence) {
        AuditTrailEvent event = event(null, status, createdAt, sequence, "1.1.1.1", "WEB");
        event.setPage(page);
        event.setActionValue(actionValue);
        event.setFrontendActionName(actionValue);
        event.setActionType("LOGGING_ACTIONS");
        event.setActionSubtype("AUTH");
        event.setApiTemplate("/api/test");
        event.setIpCountry("FR");
        event.setSessionActionSeq(sequence);
        return event;
    }

    private String doubleMojibake(String value) {
        String once = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return new String(once.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }
}
