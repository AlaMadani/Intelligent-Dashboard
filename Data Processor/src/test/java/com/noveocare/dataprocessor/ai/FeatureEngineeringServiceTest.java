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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        assertEquals(List.of("Connexion", "Envoi d'un document", "SSO Disconnect"), summary.getActionSequence());
        assertEquals(List.of("login", "documents", "logout"), summary.getRouteSequence());
    }

    @Test
    void repairsMojibakeBeforeBuildingSummary() {
        Instant base = Instant.parse("2025-01-01T10:00:00Z");
        AuditTrailEvent first = event("Connexion", "OK", base, 1, "1.1.1.1", "WEB");
        first.setPersona("self_service");
        first.setRoute("login");
        first.setCountryCode("FR");

        AuditTrailEvent second = event(doubleMojibake("Déconnexion"), "OK", base.plusSeconds(60), 2, "1.1.1.1", "WEB");
        second.setPersona("self_service");
        second.setRoute("logout");
        second.setCountryCode("FR");

        SessionSummary summary = featureEngineeringService.buildSessionSummary(
                featureEngineeringService.enrichSessionEvents(List.of(first, second)));

        assertEquals("Déconnexion", summary.getLastAction());
        assertEquals(1, summary.getHasLogout());
    }

    @Test
    void matchesReferenceSummaryForFirstCsvSession() throws Exception {
        Map<String, String> expected = readFirstDataRow(Path.of("src/main/resources/AI/audit_trail_2025_session_summary.csv"));
        List<AuditTrailEvent> sessionEvents = loadSessionEvents(
                Path.of("src/main/resources/AI/audit_trail_2025.csv"),
                expected.get("sessionId"));

        SessionSummary summary = featureEngineeringService.buildSessionSummary(
                featureEngineeringService.enrichSessionEvents(sessionEvents));

        assertEquals(expected.get("sessionId"), summary.getSessionId());
        assertEquals(expected.get("insuredId"), summary.getInsuredId());
        assertEquals(TextNormalization.normalizeLabel(expected.get("persona")), summary.getPersona());
        assertEquals(TextNormalization.normalizeLabel(expected.get("countryCode")), summary.getCountryCode());
        assertEquals(TextNormalization.normalizeLabel(expected.get("city")), summary.getCity());
        assertEquals(Integer.parseInt(expected.get("sessionNumber")), summary.getSessionNumber());
        assertEquals(TextNormalization.normalizeLabel(expected.get("firstAction")), summary.getFirstAction());
        assertEquals(TextNormalization.normalizeLabel(expected.get("lastAction")), summary.getLastAction());
        assertEquals(TextNormalization.normalizeLabel(expected.get("firstRoute")), summary.getFirstRoute());
        assertEquals(TextNormalization.normalizeLabel(expected.get("lastRoute")), summary.getLastRoute());
        assertEquals(Integer.parseInt(expected.get("totalEvents")), summary.getTotalEvents());
        assertEquals(Long.valueOf(expected.get("totalDurationSeconds")), summary.getTotalDurationSeconds());
        assertEquals(Double.parseDouble(expected.get("avgInterActionSeconds")), summary.getAvgInterActionSeconds(), 0.01);
        assertEquals(Double.parseDouble(expected.get("minInterActionSeconds")), summary.getMinInterActionSeconds(), 0.01);
        assertEquals(Double.parseDouble(expected.get("maxInterActionSeconds")), summary.getMaxInterActionSeconds(), 0.01);
        assertEquals(Integer.parseInt(expected.get("uniqueActions")), summary.getUniqueActions());
        assertEquals(Integer.parseInt(expected.get("uniqueRoutes")), summary.getUniqueRoutes());
        assertEquals(Integer.parseInt(expected.get("uniqueIpsUsed")), summary.getUniqueIpsUsed());
        assertEquals(Integer.parseInt(expected.get("uniqueDevicesUsed")), summary.getUniqueDevicesUsed());
        assertEquals(Integer.parseInt(expected.get("totalKOs")), summary.getTotalKOs());
        assertEquals(Integer.parseInt(expected.get("totalOKs")), summary.getTotalOKs());
        assertEquals(Integer.parseInt(expected.get("longestKoStreak")), summary.getLongestKoStreak());
        assertEquals(Integer.parseInt(expected.get("hasLogin")), summary.getHasLogin());
        assertEquals(Integer.parseInt(expected.get("hasLogout")), summary.getHasLogout());
        assertEquals(Integer.parseInt(expected.get("ipChanged")), summary.getIpChanged());
        assertEquals(Integer.parseInt(expected.get("deviceChanged")), summary.getDeviceChanged());
        assertEquals(Integer.parseInt(expected.get("totalDownloadActions")), summary.getTotalDownloadActions());
        assertEquals(Integer.parseInt(expected.get("maxDownloadsIn2Minutes")), summary.getMaxDownloadsIn2Minutes());
        assertEquals(Integer.parseInt(expected.get("pingPongCount")), summary.getPingPongCount());
        assertEquals(Double.parseDouble(expected.get("riskScoreMax")), summary.getRiskScoreMax(), 0.01);
        assertEquals(Double.parseDouble(expected.get("riskScoreAvg")), summary.getRiskScoreAvg(), 0.01);
        assertEquals(Integer.parseInt(expected.get("endedAbruptly")), summary.getEndedAbruptly());
        assertEquals(Integer.parseInt(expected.get("anomaly_event_count")), summary.getAnomalyEventCount());
        assertEquals(TextNormalization.normalizeLabel(expected.get("primary_anomaly_type")), summary.getPrimaryAnomalyType());
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

    private String doubleMojibake(String value) {
        String once = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return new String(once.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private List<AuditTrailEvent> loadSessionEvents(Path csvPath, String sessionId) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String[] headers = splitCsv(reader.readLine());
            Map<String, Integer> indexByName = indexHeaders(headers);
            List<AuditTrailEvent> events = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = splitCsv(line);
                if (sessionId.equals(csvValue(values, indexByName.get("sessionId")))) {
                    events.add(toEvent(values, indexByName));
                }
            }
            return events;
        }
    }

    private Map<String, String> readFirstDataRow(Path csvPath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String[] headers = splitCsv(reader.readLine());
            Map<String, Integer> indexByName = indexHeaders(headers);
            String line = reader.readLine();
            if (line != null) {
                String[] values = splitCsv(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : indexByName.entrySet()) {
                    row.put(entry.getKey(), csvValue(values, entry.getValue()));
                }
                return row;
            }
        }
        throw new IllegalArgumentException("No data rows found in " + csvPath);
    }

    private AuditTrailEvent toEvent(String[] values, Map<String, Integer> indexByName) {
        AuditTrailEvent event = new AuditTrailEvent();
        event.setInsuredId(csvValue(values, indexByName.get("insuredId")));
        event.setSessionId(csvValue(values, indexByName.get("sessionId")));
        event.setAction(csvValue(values, indexByName.get("action")));
        event.setStatus(csvValue(values, indexByName.get("status")));
        event.setCreatedAt(Instant.parse(csvValue(values, indexByName.get("createdAt"))));
        event.setSequenceInSession(Integer.parseInt(csvValue(values, indexByName.get("sequenceInSession"))));
        event.setSessionLength(Integer.parseInt(csvValue(values, indexByName.get("sessionLength"))));
        event.setIp(csvValue(values, indexByName.get("ip")));
        event.setDevice(csvValue(values, indexByName.get("device")));
        event.setType(csvValue(values, indexByName.get("type")));
        event.setCountryCode(csvValue(values, indexByName.get("countryCode")));
        event.setCity(csvValue(values, indexByName.get("city")));
        event.setMonth(csvValue(values, indexByName.get("month")));
        event.setPersona(csvValue(values, indexByName.get("persona")));
        event.setRoute(csvValue(values, indexByName.get("route")));
        event.setSessionNumber(Integer.parseInt(csvValue(values, indexByName.get("sessionNumber"))));
        event.setAnomalyType(csvValue(values, indexByName.get("anomaly_type")));
        event.setIsAnomaly(Integer.parseInt(csvValue(values, indexByName.get("is_anomaly"))));
        return event;
    }

    private Map<String, Integer> indexHeaders(String[] headers) {
        Map<String, Integer> indexByName = new LinkedHashMap<>();
        for (int index = 0; index < headers.length; index++) {
            indexByName.put(headers[index], index);
        }
        return indexByName;
    }

    private String csvValue(String[] values, Integer index) {
        if (index == null || index < 0 || index >= values.length) {
            return "";
        }
        String value = values[index];
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.replace("\"\"", "\"");
    }

    private String[] splitCsv(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }
}
