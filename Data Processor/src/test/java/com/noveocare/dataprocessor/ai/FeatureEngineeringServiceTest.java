package com.noveocare.dataprocessor.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.noveocare.dataprocessor.config.AiResourceProperties;
import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureEngineeringServiceTest {

    private FeatureEngineeringService featureEngineeringService;
    private RuntimeArtifactService runtimeArtifactService;

    @BeforeEach
    void setUp() throws Exception {
        AiResourceProperties properties = new AiResourceProperties();
        properties.setBasePath("classpath:/AI/");
        properties.setManifest("deployment_manifest.json");
        properties.setFeatureBundle("feature_bundle.json");

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        runtimeArtifactService = new RuntimeArtifactService(properties, new DefaultResourceLoader(), objectMapper);
        runtimeArtifactService.load();

        FeatureEngineeringProperties featureProperties = new FeatureEngineeringProperties();
        featureProperties.setDownloadWindowSeconds(120);
        featureProperties.setRapidActionSeconds(7);
        featureProperties.setSessionAlertRiskThreshold(60.0);
        featureEngineeringService = new FeatureEngineeringService(featureProperties, runtimeArtifactService);
    }

    @Test
    void enrichesEventsWithGeneratorStyleSessionFields() {
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
        assertEquals(1, enriched.get(2).getPingPongCount());
        assertTrue(enriched.get(1).getSessionRiskScore() >= 40.0);
    }

    @Test
    void buildsSessionSummaryAndProjectsTabularFeatures() {
        Instant base = Instant.parse("2025-01-01T10:00:00Z");
        AuditTrailEvent first = event("Connexion", "OK", base, 1, "1.1.1.1", "WEB");
        first.setPersona("self_service");
        first.setRoute("login");
        first.setCountryCode("FR");
        first.setCity("Paris");
        first.setSessionNumber(7);

        AuditTrailEvent second = event("Envoi d'un document", "OK", base.plusSeconds(60), 2, "1.1.1.1", "WEB");
        second.setPersona("self_service");
        second.setRoute("documents");
        second.setCountryCode("FR");
        second.setCity("Paris");
        second.setSessionNumber(7);
        second.setAnomalyType("data_exfiltration");
        second.setIsAnomaly(1);

        AuditTrailEvent third = event("SSO Disconnect", "OK", base.plusSeconds(120), 3, "1.1.1.1", "WEB");
        third.setPersona("self_service");
        third.setRoute("logout");
        third.setCountryCode("FR");
        third.setCity("Paris");
        third.setSessionNumber(7);

        List<AuditTrailEvent> enriched = featureEngineeringService.enrichSessionEvents(List.of(first, second, third));
        SessionSummary summary = featureEngineeringService.buildSessionSummary(enriched);
        float[] vector = featureEngineeringService.buildTabularFeatures(
                summary,
                runtimeArtifactService.getBinaryFeatureColumns(),
                runtimeArtifactService.getSessionNumericMedians());

        assertEquals(3, summary.getTotalEvents());
        assertEquals(1, summary.getTotalDownloadActions());
        assertEquals(1, summary.getHasLogin());
        assertEquals(1, summary.getHasLogout());
        assertEquals("data_exfiltration", summary.getPrimaryAnomalyType());
        assertEquals(runtimeArtifactService.getBinaryFeatureColumns().size(), vector.length);
        assertEquals(3.0f, vector[indexOf("totalEvents")], 0.0001f);
        assertEquals(1.0f, vector[indexOf("persona_self_service")], 0.0001f);
        assertEquals(1.0f, vector[indexOf("countryCode_FR")], 0.0001f);
        assertEquals(1.0f, vector[indexOf("lastRoute_logout")], 0.0001f);
    }

    private int indexOf(String column) {
        return runtimeArtifactService.getBinaryFeatureColumns().indexOf(column);
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
}
