package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36User360Response;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V36ChurnEnrichmentTest {

    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);
    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final V36NextEventPredictionService nextEventPredictionService = mock(V36NextEventPredictionService.class);

    private V36User360Service user360Service;

    @BeforeEach
    void setUp() {
        user360Service = new V36User360Service(
                redisReadService,
                sessionAnalysisRepository,
                anomalyEventRepository,
                new ObjectMapper().findAndRegisterModules(),
                nextEventPredictionService
        );
    }

    @Test
    void deduplicateByInsuredId() {
        Instant now = Instant.now();
        SessionAnalysis a1 = session("insured-A", "sess-1", 0.86, "HIGH", 80.0, "HIGH", now.minusSeconds(10), now.minusSeconds(10));
        SessionAnalysis a2 = session("insured-A", "sess-2", 0.84, "MEDIUM", 70.0, "MEDIUM", now.minusSeconds(20), now.minusSeconds(20));
        SessionAnalysis b1 = session("insured-B", "sess-3", 0.83, "HIGH", 90.0, "CRITICAL", now.minusSeconds(30), now.minusSeconds(30));

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(a1, a2, b1));
        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc(anyString())).thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc(anyString())).thenReturn(List.of());

        ApiPageResponse<Map<String, Object>> result = user360Service.getChurnUsers(null, 50);

        assertThat(result.getItems()).hasSize(2);
        List<String> insuredIds = result.getItems().stream()
                .map(item -> (String) item.get("insuredId"))
                .toList();
        assertThat(insuredIds).containsExactly("insured-A", "insured-B");
    }

    @Test
    void deduplicatePickWinnerByLatestEndTime() {
        Instant now = Instant.now();
        SessionAnalysis older = session("insured-A", "sess-old", 0.90, "HIGH", 85.0, "HIGH", now.minusSeconds(100), now.minusSeconds(100));
        SessionAnalysis newer = session("insured-A", "sess-new", 0.80, "MEDIUM", 75.0, "MEDIUM", now.minusSeconds(10), now.minusSeconds(10));

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(older, newer));
        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc(anyString())).thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc(anyString())).thenReturn(List.of());

        ApiPageResponse<Map<String, Object>> result = user360Service.getChurnUsers(null, 50);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).get("sessionId")).isEqualTo("sess-new");
        assertThat(result.getItems().get(0).get("churnProbability")).isEqualTo(0.80);
    }

    @Test
    void deduplicatePickWinnerByHighestProbabilityWhenEndTimeNull() {
        SessionAnalysis lower = sessionWithNullEndTime("insured-A", "sess-low", 0.70, "MEDIUM", 60.0, "MEDIUM");
        SessionAnalysis higher = sessionWithNullEndTime("insured-A", "sess-high", 0.95, "HIGH", 90.0, "CRITICAL");

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(lower, higher));
        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc(anyString())).thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc(anyString())).thenReturn(List.of());

        ApiPageResponse<Map<String, Object>> result = user360Service.getChurnUsers(null, 50);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).get("sessionId")).isEqualTo("sess-high");
        assertThat(result.getItems().get(0).get("churnProbability")).isEqualTo(0.95);
    }

    @Test
    void hydrateWith30DayRiskSummary() {
        Instant now = Instant.now();
        SessionAnalysis session = session("insured-A", "sess-1", 0.86, "HIGH", 80.0, "HIGH", now, now);

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(session));

        SessionAnalysis recentRiskSession = new SessionAnalysis();
        recentRiskSession.setFinalRiskScore(66.6);
        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-A"))
                .thenReturn(List.of(recentRiskSession));

        AnomalyEvent alert = new AnomalyEvent();
        alert.setAnomalyFlag(true);
        alert.setAnomalyTier("WARNING");
        AnomalyEvent criticalAlert = new AnomalyEvent();
        criticalAlert.setAnomalyFlag(true);
        criticalAlert.setAnomalyTier("CRITICAL");
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-A"))
                .thenReturn(List.of(alert, criticalAlert));

        ApiPageResponse<Map<String, Object>> result = user360Service.getChurnUsers(null, 50);

        assertThat(result.getItems()).hasSize(1);
        Map<String, Object> item = result.getItems().get(0);
        assertThat(item.get("averageRiskScoreLast30d")).isEqualTo(66.6);
        assertThat(item.get("alertCountLast30d")).isEqualTo(2);
        assertThat(item.get("criticalAlertCountLast30d")).isEqualTo(1);
    }

    @Test
    void hydrateWithNoRiskHistory() {
        Instant now = Instant.now();
        SessionAnalysis session = session("insured-A", "sess-1", 0.86, "HIGH", 80.0, "HIGH", now, now);

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(session));
        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-A")).thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-A")).thenReturn(List.of());

        ApiPageResponse<Map<String, Object>> result = user360Service.getChurnUsers(null, 50);

        assertThat(result.getItems()).hasSize(1);
        Map<String, Object> item = result.getItems().get(0);
        assertThat(item.get("averageRiskScoreLast30d")).isNull();
        assertThat(item.get("alertCountLast30d")).isEqualTo(0);
        assertThat(item.get("criticalAlertCountLast30d")).isEqualTo(0);
    }

    @Test
    void sortingByChurnProbabilityDescending() {
        Instant now = Instant.now();
        SessionAnalysis a = session("insured-A", "sess-a", 0.70, "MEDIUM", 60.0, "MEDIUM", now, now);
        SessionAnalysis b = session("insured-B", "sess-b", 0.95, "HIGH", 90.0, "CRITICAL", now, now);
        SessionAnalysis c = session("insured-C", "sess-c", 0.85, "HIGH", 80.0, "HIGH", now, now);

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(a, b, c));
        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc(anyString())).thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc(anyString())).thenReturn(List.of());

        ApiPageResponse<Map<String, Object>> result = user360Service.getChurnUsers(null, 50);

        assertThat(result.getItems()).hasSize(3);
        assertThat(result.getItems().get(0).get("insuredId")).isEqualTo("insured-B");
        assertThat(result.getItems().get(1).get("insuredId")).isEqualTo("insured-C");
        assertThat(result.getItems().get(2).get("insuredId")).isEqualTo("insured-A");
    }

    @Test
    void enrichedFieldsPresent() {
        Instant now = Instant.now();
        SessionAnalysis session = session("insured-A", "sess-1", 0.86, "HIGH", 88.57, "CRITICAL", now, now);

        when(sessionAnalysisRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(session));
        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc(anyString())).thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc(anyString())).thenReturn(List.of());

        ApiPageResponse<Map<String, Object>> result = user360Service.getChurnUsers(null, 50);

        assertThat(result.getItems()).hasSize(1);
        Map<String, Object> item = result.getItems().get(0);
        assertThat(item).containsEntry("schemaVersion", CacheKeys.V36_SCHEMA_VERSION);
        assertThat(item).containsEntry("insuredId", "insured-A");
        assertThat(item).containsEntry("sessionId", "sess-1");
        assertThat(item).containsEntry("churnProbability", 0.86);
        assertThat(item).containsEntry("churnRiskLevel", "HIGH");
        assertThat(item).containsEntry("latestFinalRiskScore", 88.57);
        assertThat(item).containsEntry("latestRiskLevel", "CRITICAL");
        assertThat(item).containsKey("averageRiskScoreLast30d");
        assertThat(item).containsKey("alertCountLast30d");
        assertThat(item).containsKey("criticalAlertCountLast30d");
        assertThat(item).containsEntry("source", "sql_fallback");
    }

    @Test
    void riskLevelFilterStillWorks() {
        Instant now = Instant.now();
        SessionAnalysis highRisk = session("insured-A", "sess-a", 0.90, "HIGH", 85.0, "HIGH", now, now);
        SessionAnalysis lowRisk = session("insured-B", "sess-b", 0.30, "LOW", 40.0, "LOW", now, now);

        when(sessionAnalysisRepository.findTop50ByChurnRiskLevelOrderByCreatedAtDesc("HIGH")).thenReturn(List.of(highRisk));
        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc(anyString())).thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc(anyString())).thenReturn(List.of());

        ApiPageResponse<Map<String, Object>> result = user360Service.getChurnUsers("HIGH", 50);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).get("insuredId")).isEqualTo("insured-A");
    }

    private static SessionAnalysis session(String insuredId, String sessionId,
                                            Double churnProbability, String churnRiskLevel,
                                            Double finalRiskScore, String riskLevel,
                                            Instant endTime, Instant createdAt) {
        SessionAnalysis s = new SessionAnalysis();
        s.setInsuredId(insuredId);
        s.setSessionId(sessionId);
        s.setChurnProbability(churnProbability);
        s.setChurnRiskLevel(churnRiskLevel);
        s.setFinalRiskScore(finalRiskScore);
        s.setRiskLevel(riskLevel);
        s.setEndTime(endTime);
        s.setCreatedAt(createdAt);
        return s;
    }

    private static SessionAnalysis sessionWithNullEndTime(String insuredId, String sessionId,
                                                           Double churnProbability, String churnRiskLevel,
                                                           Double finalRiskScore, String riskLevel) {
        SessionAnalysis s = new SessionAnalysis();
        s.setInsuredId(insuredId);
        s.setSessionId(sessionId);
        s.setChurnProbability(churnProbability);
        s.setChurnRiskLevel(churnRiskLevel);
        s.setFinalRiskScore(finalRiskScore);
        s.setRiskLevel(riskLevel);
        s.setEndTime(null);
        s.setCreatedAt(null);
        return s;
    }

    /* ------------------------------------------------------------------ */
    /*  Baseline enrichment tests                                          */
    /* ------------------------------------------------------------------ */

    @Test
    void baselineActiveHoursFromSessionTimestamps() {
        Instant t1 = Instant.parse("2026-06-01T10:15:00Z");
        Instant t2 = Instant.parse("2026-06-01T10:45:00Z");
        Instant t3 = Instant.parse("2026-06-01T11:00:00Z");
        SessionAnalysis s1 = new SessionAnalysis(); s1.setStartTime(t1); s1.setEndTime(t2);
        SessionAnalysis s2 = new SessionAnalysis(); s2.setStartTime(t3); s2.setEndTime(null);

        AnomalyEvent anomaly = new AnomalyEvent(); anomaly.setEventTime(t1);

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of(s1, s2));
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(anomaly));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        @SuppressWarnings("unchecked")
        List<Integer> hours = (List<Integer>) response.getBaseline().get("usualActiveHours");
        assertThat(hours).isNotEmpty();
        assertThat(hours.get(0)).isIn(10, 11);
    }

    @Test
    void baselineActiveHoursEmptyWhenNoTimestamps() {
        SessionAnalysis s = new SessionAnalysis(); s.setStartTime(null); s.setEndTime(null);
        AnomalyEvent a = new AnomalyEvent(); a.setEventTime(null);

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of(s));
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(a));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        assertThat(response.getBaseline().get("usualActiveHours")).isEqualTo(List.of());
    }

    @Test
    void baselineTopApiFamiliesFromEventJson() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String eventJson = om.writeValueAsString(Map.of("apiFamily", "auth"));

        AnomalyEvent a1 = new AnomalyEvent(); a1.setEventJson(eventJson);
        AnomalyEvent a2 = new AnomalyEvent(); a2.setEventJson(eventJson);
        AnomalyEvent a3 = new AnomalyEvent(); a3.setEventJson(
                om.writeValueAsString(Map.of("apiFamily", "documents")));

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(a1, a2, a3));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        @SuppressWarnings("unchecked")
        List<String> families = (List<String>) response.getBaseline().get("topApiFamilies");
        assertThat(families).containsExactly("auth", "documents");
    }

    @Test
    void baselineTopApiFamiliesEmptyWhenNoEventJson() {
        AnomalyEvent a = new AnomalyEvent(); a.setEventJson(null);

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(a));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        assertThat(response.getBaseline().get("topApiFamilies")).isEqualTo(List.of());
    }

    @Test
    void baselineNoHardcodedNAStrings() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String eventJson = om.writeValueAsString(Map.of(
                "apiFamily", "auth",
                "device", "mobile",
                "browser", "firefox"
        ));

        Instant now = Instant.now();
        SessionAnalysis s = new SessionAnalysis(); s.setStartTime(now); s.setEndTime(now);
        AnomalyEvent a = new AnomalyEvent(); a.setEventJson(eventJson); a.setEventTime(now);

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of(s));
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(a));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        assertThat(response.getBaseline().get("usualActiveHours")).isNotEqualTo("n/a");
        assertThat(response.getBaseline().get("device")).isNull(); // in top-level baseline key "usualDevice"
        assertThat(response.getBaseline().get("usualDevice")).isEqualTo("mobile");
        assertThat(response.getBaseline().get("usualBrowser")).isEqualTo("firefox");
    }

    /* ------------------------------------------------------------------ */
    /*  User360 No-op fallback for baseline test triggering Redis path     */
    /* ------------------------------------------------------------------ */

    @Test
    void baselineDeviceAndBrowserExtractedFromEventJson() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String eventJson = om.writeValueAsString(Map.of(
                "device", "mobile",
                "browser", "chrome"
        ));

        AnomalyEvent a = new AnomalyEvent(); a.setEventJson(eventJson);

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(a));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        assertThat(response.getBaseline().get("usualDevice")).isEqualTo("mobile");
        assertThat(response.getBaseline().get("usualBrowser")).isEqualTo("chrome");
    }

    @Test
    void baselineTopApiFamiliesFromSessionLlmEvidence() throws Exception {
        ObjectMapper om = new ObjectMapper();
        Map<String, Object> metadata = Map.of("apiFamily", "auth");
        String llmEvidence = om.writeValueAsString(Map.of("eventMetadata", metadata));

        SessionAnalysis s = new SessionAnalysis();
        s.setLlmExplanationEvidencePayloadJson(llmEvidence);

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of(s));
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        @SuppressWarnings("unchecked")
        List<String> families = (List<String>) response.getBaseline().get("topApiFamilies");
        assertThat(families).contains("auth");
    }

    @Test
    void baselineTopApiFamiliesFromSessionAnomalyTypeEvidence() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String evidenceJson = om.writeValueAsString(Map.of("apiFamily", "documents"));

        SessionAnalysis s = new SessionAnalysis();
        s.setAnomalyTypeEvidenceJson(evidenceJson);

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of(s));
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        @SuppressWarnings("unchecked")
        List<String> families = (List<String>) response.getBaseline().get("topApiFamilies");
        assertThat(families).contains("documents");
    }

    @Test
    void baselineTopApiFamiliesFromRouteSequence() {
        SessionAnalysis s = new SessionAnalysis();
        s.setRouteSequenceJson("[\"login\",\"documents\",\"refunds\",\"logout\"]");

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of(s));
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        @SuppressWarnings("unchecked")
        List<String> families = (List<String>) response.getBaseline().get("topApiFamilies");
        assertThat(families).contains("auth", "documents", "insured");
    }

    /* ------------------------------------------------------------------ */
    /*  Risk timeline tests                                                */
    /* ------------------------------------------------------------------ */

    @Test
    void riskTimelineDoesNotUseAnomalyTierAsRiskLevel() {
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setAnomalyTier("SESSION_RUNTIME");
        anomaly.setFinalRiskScore(68.24);
        anomaly.setRiskLevel(null);
        anomaly.setEventId("anom-00001");
        anomaly.setEventTime(Instant.parse("2026-06-19T18:47:34.644Z"));

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(anomaly));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        assertThat(response.getRiskTimeline()).hasSize(1);
        Map<String, Object> point = response.getRiskTimeline().get(0);
        assertThat(point).containsEntry("riskLevel", "HIGH");
        assertThat(point).containsEntry("pointType", "SESSION_RUNTIME");
        assertThat(point).doesNotContainEntry("riskLevel", "SESSION_RUNTIME");
    }

    @Test
    void riskTimelineDerivesRiskLevelFromScore() {
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setAnomalyTier("SESSION_RUNTIME");
        anomaly.setFinalRiskScore(40.52);
        anomaly.setRiskLevel(null);
        anomaly.setEventTime(Instant.parse("2026-06-19T18:47:34.644Z"));

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(anomaly));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        assertThat(response.getRiskTimeline()).hasSize(1);
        assertThat(response.getRiskTimeline().get(0)).containsEntry("riskLevel", "MEDIUM");
    }

    @Test
    void riskTimelineUsesPersistedRiskLevelWhenAvailable() {
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setAnomalyTier("SESSION_RUNTIME");
        anomaly.setFinalRiskScore(30.0);
        anomaly.setRiskLevel("HIGH");
        anomaly.setEventTime(Instant.parse("2026-06-19T18:47:34.644Z"));

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(anomaly));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        assertThat(response.getRiskTimeline()).hasSize(1);
        assertThat(response.getRiskTimeline().get(0)).containsEntry("riskLevel", "HIGH");
    }

    @Test
    void riskTimelinePreservesSource() {
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setAnomalyTier("SESSION_RUNTIME");
        anomaly.setFinalRiskScore(68.24);
        anomaly.setEventTime(Instant.parse("2026-06-19T18:47:34.644Z"));

        when(sessionAnalysisRepository.findTop10ByInsuredIdOrderByEndTimeDesc("insured-test"))
                .thenReturn(List.of());
        when(anomalyEventRepository.findTop10ByInsuredIdOrderByEventTimeDesc("insured-test"))
                .thenReturn(List.of(anomaly));
        when(redisReadService.readJson(CacheKeys.user360Key("insured-test")))
                .thenReturn(java.util.Optional.empty());

        V36User360Response response = user360Service.getUser360("insured-test");

        assertThat(response.getRiskTimeline()).hasSize(1);
        assertThat(response.getRiskTimeline().get(0)).containsEntry("source", "sql_fallback");
    }
}
