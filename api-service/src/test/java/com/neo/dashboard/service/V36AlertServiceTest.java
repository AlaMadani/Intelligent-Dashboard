package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36AlertInvestigationDetailDto;
import com.neo.dashboard.dto.v36.V36LiveAlertSummaryDto;
import com.neo.dashboard.dto.v36.V36ModelContributionsDto;
import com.neo.dashboard.dto.v36.V36ModelScoresDto;
import com.neo.dashboard.dto.v36.V36SequenceEvidenceDto;
import com.neo.dashboard.dto.v36.V36TabularEvidenceDto;
import com.neo.dashboard.dto.v36.V36RuleEvidenceDto;
import com.neo.dashboard.dto.v36.V36ChurnContextDto;
import com.neo.dashboard.dto.v36.V36ForecastContextDto;
import com.neo.dashboard.dto.v36.V36AnomalyTypeAttributionDto;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.LinkedHashSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V36AlertServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
    private final DashboardSnapshotFallbackService snapshotFallbackService = mock(DashboardSnapshotFallbackService.class);
    private final LlmEvidenceReadService evidenceReadService = mock(LlmEvidenceReadService.class);

    private V36AlertService service;

    @BeforeEach
    void setUp() {
        service = new V36AlertService(
                redisReadService,
                anomalyEventRepository,
                sessionAnalysisRepository,
                new ObjectMapper().findAndRegisterModules(),
                snapshotFallbackService,
                evidenceReadService
        );
    }

    @Test
    void liveAlertsUseRedisAndApplyFilters() {
        V36LiveAlertSummaryDto critical = new V36LiveAlertSummaryDto();
        critical.setEventId("evt-1");
        critical.setRiskLevel("CRITICAL");
        critical.setInsuredId("insured-1");
        critical.setTimestamp(Instant.parse("2026-05-25T02:15:00Z"));
        V36LiveAlertSummaryDto high = new V36LiveAlertSummaryDto();
        high.setEventId("evt-2");
        high.setRiskLevel("HIGH");
        high.setInsuredId("insured-2");
        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(critical, high));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                "CRITICAL", null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getEventId()).isEqualTo("evt-1");
        assertThat(result.getItems().getFirst().getSchemaVersion()).isEqualTo("v3.6.1");
    }

    @Test
    void alertDetailThrowsStructuredNotFoundWhenRedisAndSqlMiss() {
        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-missing"), com.neo.dashboard.dto.v36.V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAlertDetail("evt-missing"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Alert not found");
    }

    @Test
    void alertInvestigationMapsSessionLifecycleFromRedisPayload() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-1");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-1");
        redisDetail.setSource("redis");
        redisDetail.setSessionEndReason("explicit_logout");
        redisDetail.setSessionEndedExplicitly(true);
        redisDetail.setSessionEndedAt(Instant.parse("2026-05-25T02:30:00Z"));
        redisDetail.setSessionDurationMs(1200000L);
        redisDetail.setSessionEventCount(14);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-1"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-1");

        assertThat(result.getSessionEndReason()).isEqualTo("explicit_logout");
        assertThat(result.getSessionEndedExplicitly()).isTrue();
        assertThat(result.getSessionEndedAt()).isEqualTo(Instant.parse("2026-05-25T02:30:00Z"));
        assertThat(result.getSessionDurationMs()).isEqualTo(1200000L);
        assertThat(result.getSessionEventCount()).isEqualTo(14);
        assertThat(result.getSessionLifecycle()).isNotNull();
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEndReason", "explicit_logout");
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEndedExplicitly", true);
    }

    @Test
    void alertInvestigationRemainsCompatibleWhenSessionLifecycleAbsent() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-2");
        redisDetail.setInsuredId("insured-2");
        redisDetail.setSessionId("sess-2");
        redisDetail.setSource("redis");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-2"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-2");

        assertThat(result.getSessionEndReason()).isNull();
        assertThat(result.getSessionEndedExplicitly()).isNull();
        assertThat(result.getSessionLifecycle()).isNull();
    }

    @Test
    void sqlPayloadFallbackPreservesSessionLifecycleFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String investigationJson = mapper.writeValueAsString(Map.of(
                "sessionEndReason", "explicit_logout",
                "sessionEndedExplicitly", true,
                "sessionDurationMs", 1200000,
                "sessionEventCount", 14
        ));

        com.neo.dashboard.entity.AnomalyEvent anomaly = new com.neo.dashboard.entity.AnomalyEvent();
        anomaly.setId(100L);
        anomaly.setEventId("evt-3");
        anomaly.setInsuredId("insured-3");
        anomaly.setSessionId("sess-3");
        anomaly.setInvestigationPayloadJson(investigationJson);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-3"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-3"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-3");

        assertThat(result.getSource()).isEqualTo("sql-payload");
        assertThat(result.getSessionLifecycle()).isNotNull();
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEndReason", "explicit_logout");
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEndedExplicitly", true);
    }

    @Test
    void alertInvestigationMapsNestedSessionLifecycleFromRedis() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("sessionEndReason", "inactivity_timeout");
        lifecycle.put("sessionEndedExplicitly", false);
        lifecycle.put("sessionEndedAt", "2026-05-25T02:35:00Z");
        lifecycle.put("sessionDurationMs", 1200000);
        lifecycle.put("sessionEventCount", 14);

        String json = mapper.writeValueAsString(Map.of(
                "eventId", "evt-nested",
                "insuredId", "insured-nested",
                "sessionId", "sess-nested",
                "sessionLifecycle", lifecycle
        ));

        V36AlertInvestigationDetailDto redisDetail = mapper.readValue(json, V36AlertInvestigationDetailDto.class);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-nested"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-nested");

        assertThat(result.getSessionLifecycle()).isNotNull();
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEndReason", "inactivity_timeout");
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEndedExplicitly", false);
        assertThat(result.getSessionLifecycle()).containsEntry("sessionDurationMs", 1200000L);
    }

    @Test
    void alertInvestigationMapsFlatSessionEndReasonFieldsFromRedis() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-flat");
        redisDetail.setInsuredId("insured-flat");
        redisDetail.setSessionId("sess-flat");
        redisDetail.setSource("redis");
        redisDetail.setSessionEndReason("max_open_duration");
        redisDetail.setSessionEndedExplicitly(false);
        redisDetail.setSessionEndedAt(Instant.parse("2026-05-25T03:00:00Z"));
        redisDetail.setSessionDurationMs(3600000L);
        redisDetail.setSessionEventCount(22);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-flat"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-flat");

        assertThat(result.getSessionEndReason()).isEqualTo("max_open_duration");
        assertThat(result.getSessionEndedExplicitly()).isFalse();
        assertThat(result.getSessionEndedAt()).isEqualTo(Instant.parse("2026-05-25T03:00:00Z"));
        assertThat(result.getSessionDurationMs()).isEqualTo(3600000L);
        assertThat(result.getSessionEventCount()).isEqualTo(22);
        assertThat(result.getSessionLifecycle()).isNotNull();
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEndReason", "max_open_duration");
        assertThat(result.getSessionLifecycle()).containsEntry("sessionDurationMs", 3600000L);
    }

    @Test
    void duplicateAlertRowsDoNotCrashEndpoint() {
        V36LiveAlertSummaryDto alert1 = new V36LiveAlertSummaryDto();
        alert1.setEventId("evt-dup");
        alert1.setRiskLevel("HIGH");
        alert1.setInsuredId("insured-dup");
        V36LiveAlertSummaryDto alert2 = new V36LiveAlertSummaryDto();
        alert2.setEventId("evt-dup");
        alert2.setRiskLevel("HIGH");
        alert2.setInsuredId("insured-dup");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(alert1, alert2));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getEventId()).isEqualTo("evt-dup");
    }

    @Test
    void sqlBuildInvestigationMapsSessionDurationFromSessionAnalysis() {
        com.neo.dashboard.entity.AnomalyEvent anomaly = new com.neo.dashboard.entity.AnomalyEvent();
        anomaly.setId(200L);
        anomaly.setEventId("evt-4");
        anomaly.setInsuredId("insured-4");
        anomaly.setSessionId("sess-4");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));

        SessionAnalysis session = new SessionAnalysis();
        session.setSessionDurationSeconds(1200L);
        session.setTotalEvents(14);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-4"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-4"))
                .thenReturn(Optional.of(anomaly));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-4", "sess-4"))
                .thenReturn(Optional.of(session));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-4");

        assertThat(result.getSessionDurationMs()).isEqualTo(1200000L);
        assertThat(result.getSessionEventCount()).isEqualTo(14);
        assertThat(result.getSessionLifecycle()).isNotNull();
        assertThat(result.getSessionLifecycle()).containsEntry("sessionDurationMs", 1200000L);
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEventCount", 14);
    }

    @Test
    void alertDetailHydratesNullEvidenceFieldsFromEvidencePayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-hydrate");
        redisDetail.setInsuredId("insured-hydrate");
        redisDetail.setSessionId("sess-hydrate");
        redisDetail.setSource("redis");
        redisDetail.setRiskLevel("LOW");
        redisDetail.setFinalRiskScore(28.5);
        redisDetail.setEventMetadata(Map.of("eventAction", "LOGIN"));
        redisDetail.setTriggeredRules(List.of("RULE_001"));
        redisDetail.setId(100L);
        redisDetail.setAnomalyDbId(100L);

        assertThat(redisDetail.getSequenceEvidence()).isNull();
        assertThat(redisDetail.getTabularEvidence()).isNull();
        assertThat(redisDetail.getRuleEvidence()).isNull();
        assertThat(redisDetail.getChurnContext()).isNull();
        assertThat(redisDetail.getForecastContext()).isNull();
        assertThat(redisDetail.getAnomalyTypeAttribution()).isNull();
        assertThat(redisDetail.getModelScores()).isNull();
        assertThat(redisDetail.getModelContributions()).isNull();

        V36ModelContributionsDto contribs = new V36ModelContributionsDto();
        contribs.setXgboost(0.5);
        contribs.setLightgbm(0.3);
        contribs.setTransformer(22.5);
        contribs.setTcn(0.0);
        contribs.setRules(5.25);

        V36SequenceEvidenceDto seqEvidence = new V36SequenceEvidenceDto();
        seqEvidence.setSelectedSequenceModel("transformer");
        seqEvidence.setSequenceModelArtifact("artifact.onnx");

        V36TabularEvidenceDto tabEvidence = new V36TabularEvidenceDto();
        tabEvidence.setFeatureWarnings(Map.of());

        V36RuleEvidenceDto ruleEvidence = new V36RuleEvidenceDto();
        ruleEvidence.setRuleRiskScore(35.0);
        ruleEvidence.setTriggeredRules(List.of("RULE_001"));
        ruleEvidence.setRuleContributions(Map.of("RULE_001", 35.0));

        V36ChurnContextDto churnContext = new V36ChurnContextDto();
        churnContext.setProbability(0.72);
        churnContext.setRiskLevel("HIGH");
        churnContext.setModelName("ExtraTrees");
        churnContext.setModelArtifact("model.json");
        churnContext.setFeatureWarnings(Map.of());

        V36ForecastContextDto forecastContext = new V36ForecastContextDto();
        forecastContext.setRaw(Map.of("predictedTotalEvents", 49000));

        V36AnomalyTypeAttributionDto attr = new V36AnomalyTypeAttributionDto();
        attr.setAnomalyType("data_exfiltration");
        attr.setConfidence(0.82);
        attr.setSource("hybrid");
        attr.setEvidence(Map.of("rule", "LARGE_DOWNLOAD"));

        V36ModelScoresDto modelScores = new V36ModelScoresDto();
        modelScores.setTransformerRiskScore100(99.6);

        Map<String, Object> evidenceMap = new LinkedHashMap<>();
        evidenceMap.put("schemaVersion", CacheKeys.V36_SCHEMA_VERSION);
        evidenceMap.put("eventId", "evt-hydrate");
        evidenceMap.put("risk", Map.of("finalRiskScore", 28.5, "riskLevel", "LOW"));
        evidenceMap.put("modelScores", mapper.convertValue(modelScores, JsonNode.class));
        evidenceMap.put("modelContributions", mapper.convertValue(contribs, JsonNode.class));
        evidenceMap.put("sequenceEvidence", mapper.convertValue(seqEvidence, JsonNode.class));
        evidenceMap.put("tabularEvidence", mapper.convertValue(tabEvidence, JsonNode.class));
        evidenceMap.put("ruleEvidence", mapper.convertValue(ruleEvidence, JsonNode.class));
        evidenceMap.put("churnContext", mapper.convertValue(churnContext, JsonNode.class));
        evidenceMap.put("forecastContext", mapper.convertValue(forecastContext, JsonNode.class));
        evidenceMap.put("anomalyTypeAttribution", mapper.convertValue(attr, JsonNode.class));

        JsonNode evidenceNode = mapper.convertValue(evidenceMap, JsonNode.class);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-hydrate"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(evidenceReadService.readEvidence("evt-hydrate"))
                .thenReturn(Optional.of(evidenceNode));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-hydrate");

        assertThat(result.getSequenceEvidence()).isNotNull();
        assertThat(result.getSequenceEvidence().getSelectedSequenceModel()).isEqualTo("transformer");

        assertThat(result.getTabularEvidence()).isNotNull();
        assertThat(result.getTabularEvidence().getFeatureWarnings()).isEqualTo(Map.of());

        assertThat(result.getRuleEvidence()).isNotNull();
        assertThat(result.getRuleEvidence().getRuleRiskScore()).isEqualTo(35.0);

        assertThat(result.getChurnContext()).isNotNull();
        assertThat(result.getChurnContext().getProbability()).isEqualTo(0.72);

        assertThat(result.getForecastContext()).isNotNull();
        assertThat(result.getForecastContext().getRaw()).isNotNull();

        assertThat(result.getAnomalyTypeAttribution()).isNotNull();
        assertThat(result.getAnomalyTypeAttribution().getAnomalyType()).isEqualTo("data_exfiltration");

        assertThat(result.getModelScores()).isNotNull();
        assertThat(result.getModelScores().getTransformerRiskScore100()).isEqualTo(99.6);

        assertThat(result.getModelContributions()).isNotNull();
        assertThat(result.getModelContributions().getXgboost()).isEqualTo(0.5);
        assertThat(result.getModelContributions().getTransformer()).isEqualTo(22.5);
        assertThat(result.getModelContributions().getTcn()).isEqualTo(0.0);

        assertThat(result.getSource()).isEqualTo("redis");
    }

    @Test
    void alertDetailSkipsHydrationWhenAllEvidenceFieldsPresent() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-full");
        redisDetail.setInsuredId("insured-full");
        redisDetail.setSessionId("sess-full");
        redisDetail.setSequenceEvidence(new V36SequenceEvidenceDto());
        redisDetail.setTabularEvidence(new V36TabularEvidenceDto());
        redisDetail.setRuleEvidence(new V36RuleEvidenceDto());
        redisDetail.setChurnContext(new V36ChurnContextDto());
        redisDetail.setForecastContext(new V36ForecastContextDto());
        redisDetail.setAnomalyTypeAttribution(new V36AnomalyTypeAttributionDto());
        redisDetail.setModelScores(new V36ModelScoresDto());
        redisDetail.setModelContributions(new V36ModelContributionsDto());

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-full"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-full");

        assertThat(result.getSequenceEvidence()).isNotNull();
        assertThat(result.getTabularEvidence()).isNotNull();
        assertThat(result.getRuleEvidence()).isNotNull();
        assertThat(result.getChurnContext()).isNotNull();
        assertThat(result.getForecastContext()).isNotNull();
    }

    @Test
    void alertDetailEvidenceHydrationPreservesAlreadyPresentEventMetadata() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-meta");
        redisDetail.setInsuredId("insured-meta");
        redisDetail.setSessionId("sess-meta");
        redisDetail.setSource("redis");
        redisDetail.setEventMetadata(Map.of("eventAction", "LOGIN", "country", "MA"));

        assertThat(redisDetail.getSequenceEvidence()).isNull();
        assertThat(redisDetail.getRuleEvidence()).isNull();

        Map<String, Object> evidenceMap = new LinkedHashMap<>();
        evidenceMap.put("schemaVersion", CacheKeys.V36_SCHEMA_VERSION);
        evidenceMap.put("eventId", "evt-meta");
        evidenceMap.put("risk", Map.of("finalRiskScore", 28.5));
        evidenceMap.put("ruleEvidence", mapper.convertValue(
                new V36RuleEvidenceDto(35.0, List.of("RULE_001"), Map.of()),
                JsonNode.class));
        JsonNode evidenceNode = mapper.convertValue(evidenceMap, JsonNode.class);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-meta"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(evidenceReadService.readEvidence("evt-meta"))
                .thenReturn(Optional.of(evidenceNode));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-meta");

        assertThat(result.getEventMetadata()).isNotNull();
        assertThat(result.getEventMetadata()).containsEntry("eventAction", "LOGIN");
        assertThat(result.getRuleEvidence()).isNotNull();
        assertThat(result.getRuleEvidence().getRuleRiskScore()).isEqualTo(35.0);
    }

    @Test
    void alertDetailEvidenceHydrationHandlesMissingEvidenceGracefully() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-no-evidence");
        redisDetail.setInsuredId("insured-no-evidence");
        redisDetail.setSessionId("sess-no-evidence");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-no-evidence"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(evidenceReadService.readEvidence("evt-no-evidence"))
                .thenReturn(Optional.empty());

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-no-evidence");

        assertThat(result.getSequenceEvidence()).isNull();
        assertThat(result.getTabularEvidence()).isNull();
        assertThat(result.getRuleEvidence()).isNull();
        assertThat(result.getChurnContext()).isNull();
        assertThat(result.getForecastContext()).isNull();
        assertThat(result.getModelScores()).isNull();
        assertThat(result.getModelContributions()).isNull();
    }

    @Test
    void alertDetailEvidenceHydrationFillsEventMetadataWhenNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-meta-hydrate");
        redisDetail.setInsuredId("insured-meta-hydrate");
        redisDetail.setSessionId("sess-meta-hydrate");

        assertThat(redisDetail.getSequenceEvidence()).isNull();
        assertThat(redisDetail.getEventMetadata()).isNull();

        Map<String, Object> evidenceMap = new LinkedHashMap<>();
        evidenceMap.put("schemaVersion", CacheKeys.V36_SCHEMA_VERSION);
        evidenceMap.put("eventId", "evt-meta-hydrate");
        evidenceMap.put("eventMetadata", Map.of("eventAction", "DOWNLOAD_DOCUMENT", "country", "MA", "httpMethod", "GET"));
        evidenceMap.put("ruleEvidence", mapper.convertValue(
                new V36RuleEvidenceDto(25.0, List.of("RULE_002"), Map.of()),
                JsonNode.class));
        JsonNode evidenceNode = mapper.convertValue(evidenceMap, JsonNode.class);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-meta-hydrate"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(evidenceReadService.readEvidence("evt-meta-hydrate"))
                .thenReturn(Optional.of(evidenceNode));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-meta-hydrate");

        assertThat(result.getEventMetadata()).isNotNull();
        assertThat(result.getEventMetadata()).containsEntry("eventAction", "DOWNLOAD_DOCUMENT");
        assertThat(result.getEventMetadata()).containsEntry("country", "MA");
        assertThat(result.getRuleEvidence()).isNotNull();
        assertThat(result.getRuleEvidence().getRuleRiskScore()).isEqualTo(25.0);
    }
    @Test
    void dedupKeepsRicherRowWhenIncompleteDuplicateExists() {
        V36LiveAlertSummaryDto sparse = new V36LiveAlertSummaryDto();
        sparse.setEventId("evt-dup-rich");
        sparse.setFinalRiskScore(70.22);
        sparse.setSource("sql_fallback");

        V36LiveAlertSummaryDto rich = new V36LiveAlertSummaryDto();
        rich.setEventId("evt-dup-rich");
        rich.setRiskLevel("HIGH");
        rich.setTimestamp(Instant.parse("2026-06-18T10:00:00Z"));
        rich.setEventAction("LOGIN");
        rich.setFinalRiskScore(70.22);
        rich.setSource("sql_fallback");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(sparse, rich));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getEventId()).isEqualTo("evt-dup-rich");
        assertThat(result.getItems().getFirst().getRiskLevel()).isEqualTo("HIGH");
        assertThat(result.getItems().getFirst().getTimestamp()).isNotNull();
        assertThat(result.getItems().getFirst().getEventAction()).isEqualTo("LOGIN");
    }

    @Test
    void hydratesRiskLevelFromFinalRiskScoreWhenMissing() {
        V36LiveAlertSummaryDto alert = new V36LiveAlertSummaryDto();
        alert.setEventId("evt-hydrate-risk");
        alert.setInsuredId("insured-hydrate");
        alert.setFinalRiskScore(70.22);

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(alert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getRiskLevel()).isEqualTo("HIGH");
    }

    @Test
    void doesNotOverwriteExistingRiskLevelWithHydration() {
        V36LiveAlertSummaryDto alert = new V36LiveAlertSummaryDto();
        alert.setEventId("evt-no-overwrite");
        alert.setInsuredId("insured-no-overwrite");
        alert.setRiskLevel("LOW");
        alert.setFinalRiskScore(95.0);

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(alert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getRiskLevel()).isEqualTo("LOW");
    }

    @Test
    void addsWarningWhenRiskLevelUnavailable() {
        V36LiveAlertSummaryDto alert = new V36LiveAlertSummaryDto();
        alert.setEventId("evt-warn-risk");
        alert.setInsuredId("insured-warn");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(alert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getRiskLevel()).isNull();
        assertThat(result.getItems().getFirst().getWarnings()).contains("Risk level unavailable");
    }

    @Test
    void snapshotFallbackDeduplicatesByEventId() {
        V36LiveAlertSummaryDto incomplete = new V36LiveAlertSummaryDto();
        incomplete.setEventId("evt-snap-dup");
        incomplete.setFinalRiskScore(45.0);

        V36LiveAlertSummaryDto complete = new V36LiveAlertSummaryDto();
        complete.setEventId("evt-snap-dup");
        complete.setRiskLevel("MEDIUM");
        complete.setTimestamp(Instant.parse("2026-06-18T12:00:00Z"));
        complete.setEventAction("API_CALL");
        complete.setFinalRiskScore(45.0);
        complete.setAnomalyType("velocity");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 100))
                .thenReturn(List.of(incomplete, complete));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getEventId()).isEqualTo("evt-snap-dup");
        assertThat(result.getItems().getFirst().getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(result.getItems().getFirst().getTimestamp()).isNotNull();
        assertThat(result.getItems().getFirst().getEventAction()).isEqualTo("API_CALL");
        assertThat(result.getItems().getFirst().getSource()).isEqualTo("sql_fallback");
    }

    @Test
    void detailEndpointHydratesTimestampFromAnomalyEvent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant eventTime = Instant.parse("2026-06-18T08:30:00Z");

        String investigationJson = mapper.writeValueAsString(Map.of(
                "eventId", "evt-ts-hydrate",
                "insuredId", "insured-ts",
                "sessionId", "sess-ts"
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(300L);
        anomaly.setEventId("evt-ts-hydrate");
        anomaly.setInsuredId("insured-ts");
        anomaly.setSessionId("sess-ts");
        anomaly.setEventTime(eventTime);
        anomaly.setInvestigationPayloadJson(investigationJson);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-ts-hydrate"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-ts-hydrate"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-ts-hydrate");

        assertThat(result.getTimestamp()).isEqualTo(eventTime);
    }

    @Test
    void dedupWithRichnessPicksHighestScoreForMultipleSameEventId() {
        V36LiveAlertSummaryDto sparse = new V36LiveAlertSummaryDto();
        sparse.setEventId("evt-score-test");
        sparse.setFinalRiskScore(30.0);

        V36LiveAlertSummaryDto medium = new V36LiveAlertSummaryDto();
        medium.setEventId("evt-score-test");
        medium.setFinalRiskScore(30.0);
        medium.setRiskLevel("LOW");
        medium.setTimestamp(Instant.parse("2026-06-18T14:00:00Z"));

        V36LiveAlertSummaryDto richest = new V36LiveAlertSummaryDto();
        richest.setEventId("evt-score-test");
        richest.setRiskLevel("LOW");
        richest.setTimestamp(Instant.parse("2026-06-18T14:00:00Z"));
        richest.setEventAction("DOWNLOAD");
        richest.setFinalRiskScore(30.0);
        richest.setAnomalyType("data_exfiltration");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(sparse, medium, richest));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getEventId()).isEqualTo("evt-score-test");
        assertThat(result.getItems().getFirst().getEventAction()).isEqualTo("DOWNLOAD");
        assertThat(result.getItems().getFirst().getAnomalyType()).isEqualTo("data_exfiltration");
    }

    @Test
    void riskLevelHydrationUsesCorrectThresholds() {
        V36LiveAlertSummaryDto critical = new V36LiveAlertSummaryDto();
        critical.setEventId("evt-score-80");
        critical.setFinalRiskScore(85.0);

        V36LiveAlertSummaryDto high = new V36LiveAlertSummaryDto();
        high.setEventId("evt-score-60");
        high.setFinalRiskScore(65.0);

        V36LiveAlertSummaryDto medium = new V36LiveAlertSummaryDto();
        medium.setEventId("evt-score-35");
        medium.setFinalRiskScore(50.0);

        V36LiveAlertSummaryDto low = new V36LiveAlertSummaryDto();
        low.setEventId("evt-score-0");
        low.setFinalRiskScore(20.0);

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(critical, high, medium, low));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(4);
        assertThat(result.getItems()).extracting(V36LiveAlertSummaryDto::getRiskLevel)
                .containsExactlyInAnyOrder("CRITICAL", "HIGH", "MEDIUM", "LOW");
    }

    @Test
    void detailEndpointHydratesTimestampFromDetectedAtWhenEventTimeNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant detectedAt = Instant.parse("2026-06-17T22:00:00Z");

        String investigationJson = mapper.writeValueAsString(Map.of(
                "eventId", "evt-detected",
                "insuredId", "insured-detected",
                "sessionId", "sess-detected"
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(400L);
        anomaly.setEventId("evt-detected");
        anomaly.setInsuredId("insured-detected");
        anomaly.setSessionId("sess-detected");
        anomaly.setDetectedAt(detectedAt);
        anomaly.setInvestigationPayloadJson(investigationJson);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-detected"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-detected"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-detected");

        assertThat(result.getTimestamp()).isEqualTo(detectedAt);
    }

    @Test
    void liveRowHydratesTimestampFromAnomalyEventViaSnapshotPath() {
        V36LiveAlertSummaryDto snapshotRow = new V36LiveAlertSummaryDto();
        snapshotRow.setEventId("evt-snap-ts");
        snapshotRow.setFinalRiskScore(49.84);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-snap-ts");
        anomaly.setEventTime(Instant.parse("2026-06-18T09:42:12.277Z"));

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 100))
                .thenReturn(List.of(snapshotRow));
        when(anomalyEventRepository.findByEventIdIn(List.of("evt-snap-ts")))
                .thenReturn(List.of(anomaly));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getTimestamp())
                .isEqualTo(Instant.parse("2026-06-18T09:42:12.277Z"));
    }

    @Test
    void liveRowHydratesEventMetadataFromAnomalyEventJson() {
        V36LiveAlertSummaryDto snapshotRow = new V36LiveAlertSummaryDto();
        snapshotRow.setEventId("evt-snap-meta");
        snapshotRow.setFinalRiskScore(49.84);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-snap-meta");
        anomaly.setEventTime(Instant.parse("2026-06-18T09:42:12.277Z"));
        anomaly.setEventJson("{\"eventAction\":\"update_address\",\"apiTemplate\":\"/insured/address\",\"apiFamily\":\"insured\"}");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 100))
                .thenReturn(List.of(snapshotRow));
        when(anomalyEventRepository.findByEventIdIn(List.of("evt-snap-meta")))
                .thenReturn(List.of(anomaly));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getEventAction()).isEqualTo("update_address");
        assertThat(result.getItems().getFirst().getApiTemplate()).isEqualTo("/insured/address");
        assertThat(result.getItems().getFirst().getApiFamily()).isEqualTo("insured");
    }

    @Test
    void liveRowHydratesModelScoresFromAnomalyEvent() {
        V36LiveAlertSummaryDto snapshotRow = new V36LiveAlertSummaryDto();
        snapshotRow.setEventId("evt-snap-scores");
        snapshotRow.setFinalRiskScore(49.84);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-snap-scores");
        anomaly.setEventTime(Instant.parse("2026-06-18T09:42:12.277Z"));
        anomaly.setXgboostAnomalyScore100(25.29);
        anomaly.setLightgbmAlertScore100(89.56);
        anomaly.setTransformerRiskScore100(69.98);

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 100))
                .thenReturn(List.of(snapshotRow));
        when(anomalyEventRepository.findByEventIdIn(List.of("evt-snap-scores")))
                .thenReturn(List.of(anomaly));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getXgboostAnomalyScore100()).isEqualTo(25.29);
        assertThat(result.getItems().getFirst().getLightgbmAlertScore100()).isEqualTo(89.56);
        assertThat(result.getItems().getFirst().getTransformerRiskScore100()).isEqualTo(69.98);
        assertThat(result.getItems().getFirst().getTcnRiskScore100()).isNull();
    }

    @Test
    void liveRowHydratesModelContributionsFromAnomalyEvent() {
        V36LiveAlertSummaryDto snapshotRow = new V36LiveAlertSummaryDto();
        snapshotRow.setEventId("evt-snap-contrib");
        snapshotRow.setFinalRiskScore(49.84);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-snap-contrib");
        anomaly.setEventTime(Instant.parse("2026-06-18T09:42:12.277Z"));
        anomaly.setModelContributionsJson("{\"xgboost\":8.6,\"lightgbm\":25.37,\"transformer\":15.86,\"tcn\":0.0,\"rules\":0.0}");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 100))
                .thenReturn(List.of(snapshotRow));
        when(anomalyEventRepository.findByEventIdIn(List.of("evt-snap-contrib")))
                .thenReturn(List.of(anomaly));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getModelContributions()).isNotNull();
        assertThat(result.getItems().getFirst().getModelContributions().getXgboost()).isEqualTo(8.6);
        assertThat(result.getItems().getFirst().getModelContributions().getLightgbm()).isEqualTo(25.37);
    }

    @Test
    void liveRowHydratesFromInvestigationPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String investigationJson = mapper.writeValueAsString(Map.of(
                "eventId", "evt-snap-inv",
                "timestamp", "2026-06-18T09:42:12.277Z",
                "eventMetadata", Map.of(
                        "eventAction", "update_address",
                        "apiTemplate", "/insured/address",
                        "apiFamily", "insured"
                )
        ));

        V36LiveAlertSummaryDto snapshotRow = new V36LiveAlertSummaryDto();
        snapshotRow.setEventId("evt-snap-inv");
        snapshotRow.setFinalRiskScore(49.84);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-snap-inv");
        anomaly.setInvestigationPayloadJson(investigationJson);

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 100))
                .thenReturn(List.of(snapshotRow));
        when(anomalyEventRepository.findByEventIdIn(List.of("evt-snap-inv")))
                .thenReturn(List.of(anomaly));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getTimestamp())
                .isEqualTo(Instant.parse("2026-06-18T09:42:12.277Z"));
        assertThat(result.getItems().getFirst().getEventAction()).isEqualTo("update_address");
        assertThat(result.getItems().getFirst().getApiTemplate()).isEqualTo("/insured/address");
        assertThat(result.getItems().getFirst().getApiFamily()).isEqualTo("insured");
    }

    @Test
    void liveRowDoesNotOverwriteNonNullValuesFromStoredPayload() {
        Instant originalTs = Instant.parse("2026-06-18T08:00:00Z");

        V36LiveAlertSummaryDto snapshotRow = new V36LiveAlertSummaryDto();
        snapshotRow.setEventId("evt-snap-no-overwrite");
        snapshotRow.setRiskLevel("LOW");
        snapshotRow.setTimestamp(originalTs);
        snapshotRow.setEventAction("existing_action");
        snapshotRow.setFinalRiskScore(49.84);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-snap-no-overwrite");
        anomaly.setEventTime(Instant.parse("2026-06-18T10:00:00Z"));
        anomaly.setRiskLevel("CRITICAL");
        anomaly.setEventJson("{\"eventAction\":\"different_action\"}");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 100))
                .thenReturn(List.of(snapshotRow));
        when(anomalyEventRepository.findByEventIdIn(List.of("evt-snap-no-overwrite")))
                .thenReturn(List.of(anomaly));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getTimestamp()).isEqualTo(originalTs);
        assertThat(result.getItems().getFirst().getRiskLevel()).isEqualTo("LOW");
        assertThat(result.getItems().getFirst().getEventAction()).isEqualTo("existing_action");
    }

    @Test
    void liveRowHydratesEvidenceAvailabilityFromAnomaly() {
        V36LiveAlertSummaryDto snapshotRow = new V36LiveAlertSummaryDto();
        snapshotRow.setEventId("evt-snap-evidence");
        snapshotRow.setFinalRiskScore(49.84);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-snap-evidence");
        anomaly.setEventTime(Instant.parse("2026-06-18T09:42:12.277Z"));
        anomaly.setLlmExplanationEvidencePayloadJson("{\"schemaVersion\":\"v3.6.1\"}");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 100))
                .thenReturn(List.of(snapshotRow));
        when(anomalyEventRepository.findByEventIdIn(List.of("evt-snap-evidence")))
                .thenReturn(List.of(anomaly));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getLlmEvidencePayloadAvailable()).isTrue();
    }

    @Test
    void liveRowHydratesInRedisPathFromAnomalyEvent() {
        V36LiveAlertSummaryDto redisRow = new V36LiveAlertSummaryDto();
        redisRow.setEventId("evt-redis-ts");
        redisRow.setFinalRiskScore(49.84);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-redis-ts");
        anomaly.setEventTime(Instant.parse("2026-06-18T09:42:12.277Z"));

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of(redisRow));
        when(anomalyEventRepository.findByEventIdIn(List.of("evt-redis-ts")))
                .thenReturn(List.of(anomaly));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getTimestamp())
                .isEqualTo(Instant.parse("2026-06-18T09:42:12.277Z"));
    }

    @Test
    void liveRowHydratesFromEvidenceNodeWhenAnomalyHasEvidencePayload() {
        V36LiveAlertSummaryDto snapshotRow = new V36LiveAlertSummaryDto();
        snapshotRow.setEventId("evt-snap-evidence-contrib");
        snapshotRow.setFinalRiskScore(49.84);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setEventId("evt-snap-evidence-contrib");
        anomaly.setEventTime(Instant.parse("2026-06-18T09:42:12.277Z"));
        anomaly.setLlmExplanationEvidencePayloadJson("{\"schemaVersion\":\"v3.6.1\",\"modelContributions\":{\"xgboost\":8.6,\"lightgbm\":25.37}}");

        when(redisReadService.readItems(CacheKeys.ALERTS_LIVE_V36, V36LiveAlertSummaryDto.class, 100))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 100))
                .thenReturn(List.of(snapshotRow));
        when(anomalyEventRepository.findByEventIdIn(List.of("evt-snap-evidence-contrib")))
                .thenReturn(List.of(anomaly));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().getFirst().getModelContributions()).isNotNull();
        assertThat(result.getItems().getFirst().getModelContributions().getXgboost()).isEqualTo(8.6);
        assertThat(result.getItems().getFirst().getLlmEvidencePayloadAvailable()).isTrue();
    }
}
