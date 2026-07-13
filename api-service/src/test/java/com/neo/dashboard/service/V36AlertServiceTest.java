package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36AlertInvestigationDetailDto;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto;
import com.neo.dashboard.dto.v36.V36LiveAlertSummaryDto;
import com.neo.dashboard.dto.v36.V36ModelContributionsDto;
import com.neo.dashboard.dto.v36.V36ModelScoresDto;
import com.neo.dashboard.dto.v36.V36SequenceEvidenceDto;
import com.neo.dashboard.dto.v36.V36TabularEvidenceDto;
import com.neo.dashboard.dto.v36.V36RuleEvidenceDto;
import com.neo.dashboard.dto.v36.V36ChurnContextDto;
import com.neo.dashboard.dto.v36.V36ForecastContextDto;
import com.neo.dashboard.dto.v36.V36AnomalyTypeAttributionDto;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto.V36NextEventPredictionDeviationDto;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto.V36NextEventPredictionHeadItemDto;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;

import com.neo.dashboard.entity.SessionAnalysis;
import java.util.LinkedHashSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V36AlertServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);
    private final SessionAnalysisRepository sessionAnalysisRepository = mock(SessionAnalysisRepository.class);
    private final DashboardSnapshotFallbackService snapshotFallbackService = mock(DashboardSnapshotFallbackService.class);
    private final LlmEvidenceReadService evidenceReadService = mock(LlmEvidenceReadService.class);
    private final V36NextEventPredictionService nextEventPredictionService = mock(V36NextEventPredictionService.class);

    private V36AlertService service;

    @BeforeEach
    void setUp() {
        service = new V36AlertService(
                redisReadService,
                anomalyEventRepository,
                sessionAnalysisRepository,
                new ObjectMapper().findAndRegisterModules(),
                snapshotFallbackService,
                evidenceReadService,
                nextEventPredictionService
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
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
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

        assertThat(result.getSource()).isEqualTo("sql");
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                eq(V36LiveAlertSummaryDto.class), eq("alerts"), eq("alerts:latest"), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                eq(V36LiveAlertSummaryDto.class), eq("alerts"), eq("alerts:latest"), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                eq(V36LiveAlertSummaryDto.class), eq("alerts"), eq("alerts:latest"), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                eq(V36LiveAlertSummaryDto.class), eq("alerts"), eq("alerts:latest"), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                eq(V36LiveAlertSummaryDto.class), eq("alerts"), eq("alerts:latest"), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                eq(V36LiveAlertSummaryDto.class), eq("alerts"), eq("alerts:latest"), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                eq(V36LiveAlertSummaryDto.class), eq("alerts"), eq("alerts:latest"), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                eq(V36LiveAlertSummaryDto.class), eq("alerts"), eq("alerts:latest"), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
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

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(
                eq(V36LiveAlertSummaryDto.class), eq("alerts"), eq("alerts:latest"), anyInt()))
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

    /* ------------------------------------------------------------------ */
    /*  Alert enrichment: event metadata from investigation/evidence       */
    /* ------------------------------------------------------------------ */

    @Test
    void userAlertsSqlFallbackHydratesEventActionFromInvestigationPayload() throws Exception {
        when(redisReadService.readItems(eq(CacheKeys.userAlertsKey("insured-1")), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ObjectMapper om = new ObjectMapper();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventAction", "Connexion");
        metadata.put("apiTemplate", "/auth/login");
        metadata.put("apiFamily", "auth");
        Map<String, Object> invPayload = Map.of("eventMetadata", metadata);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1L);
        anomaly.setEventId("evt-1");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-06-01T10:00:00Z"));
        anomaly.setRiskLevel("HIGH");
        anomaly.setAnomalyType("ANOMALY");
        anomaly.setInvestigationPayloadJson(om.writeValueAsString(invPayload));
        anomaly.setEventJson(null);

        Page<AnomalyEvent> page = mock(Page.class);
        when(page.getContent()).thenReturn(List.of(anomaly));
        when(page.hasNext()).thenReturn(false);
        when(anomalyEventRepository.searchV36Alerts(
                any(), any(), eq("insured-1"), any(), any(), any(), any()))
                .thenReturn(page);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        V36LiveAlertSummaryDto alert = result.getItems().getFirst();
        assertThat(alert.getEventAction()).isEqualTo("Connexion");
        assertThat(alert.getApiTemplate()).isEqualTo("/auth/login");
        assertThat(alert.getApiFamily()).isEqualTo("auth");
    }

    @Test
    void userAlertsSqlFallbackHydratesFromLlmEvidenceWhenInvestigationMissing() throws Exception {
        when(redisReadService.readItems(eq(CacheKeys.userAlertsKey("insured-1")), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ObjectMapper om = new ObjectMapper();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventAction", "Paiement");
        metadata.put("apiTemplate", "/api/pay");
        metadata.put("apiFamily", "payments");
        Map<String, Object> evidencePayload = Map.of("eventMetadata", metadata);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1L);
        anomaly.setEventId("evt-2");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-2");
        anomaly.setEventTime(Instant.parse("2026-06-01T11:00:00Z"));
        anomaly.setRiskLevel("CRITICAL");
        anomaly.setAnomalyType("FRAUD");
        anomaly.setEventJson(null);
        anomaly.setInvestigationPayloadJson(null);
        anomaly.setLlmExplanationEvidencePayloadJson(om.writeValueAsString(evidencePayload));

        Page<AnomalyEvent> page = mock(Page.class);
        when(page.getContent()).thenReturn(List.of(anomaly));
        when(page.hasNext()).thenReturn(false);
        when(anomalyEventRepository.searchV36Alerts(
                any(), any(), eq("insured-1"), any(), any(), any(), any()))
                .thenReturn(page);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        V36LiveAlertSummaryDto alert = result.getItems().getFirst();
        assertThat(alert.getEventAction()).isEqualTo("Paiement");
        assertThat(alert.getApiTemplate()).isEqualTo("/api/pay");
        assertThat(alert.getApiFamily()).isEqualTo("payments");
    }

    @Test
    void userAlertsDoesNotOverwriteExistingEventActionWithNull() throws Exception {
        when(redisReadService.readItems(eq(CacheKeys.userAlertsKey("insured-1")), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ObjectMapper om = new ObjectMapper();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventAction", "Connexion");
        metadata.put("apiTemplate", "/auth/login");
        metadata.put("apiFamily", "auth");
        Map<String, Object> invPayload = Map.of("eventMetadata", metadata);

        String eventJson = om.writeValueAsString(Map.of(
                "eventAction", "Déconnexion",
                "apiTemplate", "/auth/logout",
                "apiFamily", "auth"
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1L);
        anomaly.setEventId("evt-3");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-3");
        anomaly.setEventTime(Instant.parse("2026-06-01T12:00:00Z"));
        anomaly.setRiskLevel("HIGH");
        anomaly.setAnomalyType("ANOMALY");
        anomaly.setEventJson(eventJson);
        anomaly.setInvestigationPayloadJson(om.writeValueAsString(invPayload));

        Page<AnomalyEvent> page = mock(Page.class);
        when(page.getContent()).thenReturn(List.of(anomaly));
        when(page.hasNext()).thenReturn(false);
        when(anomalyEventRepository.searchV36Alerts(
                any(), any(), eq("insured-1"), any(), any(), any(), any()))
                .thenReturn(page);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        V36LiveAlertSummaryDto alert = result.getItems().getFirst();
        assertThat(alert.getEventAction()).isEqualTo("Déconnexion");
        assertThat(alert.getApiTemplate()).isEqualTo("/auth/logout");
        assertThat(alert.getApiFamily()).isEqualTo("auth");
    }

    @Test
    void userAlertsSqlFallbackHydratesFromSessionWhenAnomalyPayloadEmpty() throws Exception {
        when(redisReadService.readItems(eq(CacheKeys.userAlertsKey("insured-1")), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ObjectMapper om = new ObjectMapper();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("eventAction", "Connexion");
        metadata.put("apiTemplate", "/auth/login");
        metadata.put("apiFamily", "auth");
        String sessionEvidence = om.writeValueAsString(Map.of("eventId", "evt-session", "eventMetadata", metadata));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1L);
        anomaly.setEventId("evt-session");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-06-01T13:00:00Z"));
        anomaly.setRiskLevel("HIGH");
        anomaly.setAnomalyType("ANOMALY");
        anomaly.setEventJson(null);
        anomaly.setInvestigationPayloadJson(null);
        anomaly.setLlmExplanationEvidencePayloadJson(null);

        SessionAnalysis session = new SessionAnalysis();
        session.setLlmExplanationEvidencePayloadJson(sessionEvidence);

        when(anomalyEventRepository.searchV36Alerts(
                any(), any(), eq("insured-1"), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Page<AnomalyEvent> page = mock(Page.class);
                    when(page.getContent()).thenReturn(List.of(anomaly));
                    when(page.hasNext()).thenReturn(false);
                    return page;
                });
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-1"))
                .thenReturn(java.util.Optional.of(session));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        V36LiveAlertSummaryDto alert = result.getItems().getFirst();
        assertThat(alert.getEventAction()).isEqualTo("Connexion");
        assertThat(alert.getApiTemplate()).isEqualTo("/auth/login");
        assertThat(alert.getApiFamily()).isEqualTo("auth");
    }

    @Test
    void userAlertsSessionHydrationSetsLlmEvidenceAvailable() throws Exception {
        when(redisReadService.readItems(eq(CacheKeys.userAlertsKey("insured-1")), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ObjectMapper om = new ObjectMapper();
        String sessionEvidence = om.writeValueAsString(Map.of("eventId", "evt-sess-llm", "eventMetadata", Map.of("eventAction", "Test")));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(2L);
        anomaly.setEventId("evt-sess-llm");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-2");
        anomaly.setEventTime(Instant.parse("2026-06-01T14:00:00Z"));
        anomaly.setRiskLevel("LOW");
        anomaly.setAnomalyType("TEST");
        anomaly.setEventJson(null);
        anomaly.setInvestigationPayloadJson(null);
        anomaly.setLlmExplanationEvidencePayloadJson(null);

        SessionAnalysis session = new SessionAnalysis();
        session.setLlmExplanationEvidencePayloadJson(sessionEvidence);

        when(anomalyEventRepository.searchV36Alerts(
                any(), any(), eq("insured-1"), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Page<AnomalyEvent> page = mock(Page.class);
                    when(page.getContent()).thenReturn(List.of(anomaly));
                    when(page.hasNext()).thenReturn(false);
                    return page;
                });
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-2"))
                .thenReturn(java.util.Optional.of(session));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        V36LiveAlertSummaryDto alert = result.getItems().getFirst();
        assertThat(alert.getLlmEvidencePayloadAvailable()).isTrue();
    }

    @Test
    void userAlertsZsetReturnsAlertsWithSourceRedisZset() {
        V36LiveAlertSummaryDto alert = new V36LiveAlertSummaryDto();
        alert.setEventId("evt-user-zset");
        alert.setInsuredId("insured-1");
        alert.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        alert.setRiskLevel("HIGH");
        when(redisReadService.readZSetAlertItems(
                eq(CacheKeys.userAlertsZSetKey("insured-1")),
                eq(CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX),
                eq(V36LiveAlertSummaryDto.class),
                anyInt()))
                .thenReturn(List.of(alert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-user-zset");
        assertThat(result.getItems().get(0).getSource()).isEqualTo("redis_zset");
    }

    @Test
    void userAlertsZsetPreferZsetOverLegacy() {
        V36LiveAlertSummaryDto zsetAlert = new V36LiveAlertSummaryDto();
        zsetAlert.setEventId("evt-user-zset");
        zsetAlert.setInsuredId("insured-1");
        zsetAlert.setTimestamp(Instant.parse("2026-06-02T00:00:00Z"));
        zsetAlert.setRiskLevel("HIGH");

        V36LiveAlertSummaryDto legacyAlert = new V36LiveAlertSummaryDto();
        legacyAlert.setEventId("evt-user-legacy");
        legacyAlert.setInsuredId("insured-1");
        legacyAlert.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        legacyAlert.setRiskLevel("HIGH");

        when(redisReadService.readZSetAlertItems(
                eq(CacheKeys.userAlertsZSetKey("insured-1")),
                eq(CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX),
                eq(V36LiveAlertSummaryDto.class),
                anyInt()))
                .thenReturn(List.of(zsetAlert));
        when(redisReadService.readItems(eq(CacheKeys.userAlertsKey("insured-1")), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(legacyAlert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-user-zset");
        assertThat(result.getItems().get(0).getSource()).isEqualTo("redis_zset");
    }

    @Test
    void userAlertsZsetEmptyFallsToLegacy() {
        V36LiveAlertSummaryDto legacyAlert = new V36LiveAlertSummaryDto();
        legacyAlert.setEventId("evt-user-legacy");
        legacyAlert.setInsuredId("insured-1");
        legacyAlert.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        legacyAlert.setRiskLevel("HIGH");

        when(redisReadService.readZSetAlertItems(
                eq(CacheKeys.userAlertsZSetKey("insured-1")),
                eq(CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX),
                eq(V36LiveAlertSummaryDto.class),
                anyInt()))
                .thenReturn(List.of());
        when(redisReadService.readItems(eq(CacheKeys.userAlertsKey("insured-1")), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(legacyAlert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-user-legacy");
        assertThat(result.getItems().get(0).getSource()).isEqualTo("redis_legacy_list");
        assertThat(result.getItems().get(0).getWarnings()).contains("canonical_zset_empty_legacy_list_used");
    }

    @Test
    void userAlertsSessionHydrationRespectsExactEventIdMatch() throws Exception {
        when(redisReadService.readItems(eq(CacheKeys.userAlertsKey("insured-1")), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ObjectMapper om = new ObjectMapper();
        Map<String, Object> metadataEvt5 = new LinkedHashMap<>();
        metadataEvt5.put("eventAction", "ActionForEvt5");
        String sessionEvidence = om.writeValueAsString(Map.of(
                "eventId", "evt-4",
                "eventMetadata", metadataEvt5
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(4L);
        anomaly.setEventId("evt-4");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-same");
        anomaly.setEventTime(Instant.parse("2026-06-01T15:00:00Z"));
        anomaly.setRiskLevel("HIGH");
        anomaly.setAnomalyType("ANOMALY");
        anomaly.setEventJson(null);
        anomaly.setInvestigationPayloadJson(null);
        anomaly.setLlmExplanationEvidencePayloadJson(null);

        SessionAnalysis session = new SessionAnalysis();
        session.setLlmExplanationEvidencePayloadJson(sessionEvidence);

        when(anomalyEventRepository.searchV36Alerts(
                any(), any(), eq("insured-1"), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Page<AnomalyEvent> page = mock(Page.class);
                    when(page.getContent()).thenReturn(List.of(anomaly));
                    when(page.hasNext()).thenReturn(false);
                    return page;
                });
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-same"))
                .thenReturn(java.util.Optional.of(session));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        V36LiveAlertSummaryDto alert = result.getItems().getFirst();
        assertThat(alert.getEventAction()).isEqualTo("ActionForEvt5");
    }

    @Test
    void liveAlertsRedisReturnsAlertsInTimestampDescendingOrder() {
        V36LiveAlertSummaryDto older = new V36LiveAlertSummaryDto();
        older.setEventId("evt-old");
        older.setTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        V36LiveAlertSummaryDto newer = new V36LiveAlertSummaryDto();
        newer.setEventId("evt-new");
        newer.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(older, newer));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-new");
        assertThat(result.getItems().get(1).getEventId()).isEqualTo("evt-old");
    }

    @Test
    void liveAlertsRedisSortsByTimestampThenCreatedAtThenEventIdAsTiebreaker() {
        Instant sameTime = Instant.parse("2026-06-01T00:00:00Z");
        V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
        a.setEventId("evt-a");
        a.setTimestamp(sameTime);
        a.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        V36LiveAlertSummaryDto b = new V36LiveAlertSummaryDto();
        b.setEventId("evt-b");
        b.setTimestamp(sameTime);
        b.setCreatedAt(Instant.parse("2026-06-01T00:00:00Z"));
        V36LiveAlertSummaryDto c = new V36LiveAlertSummaryDto();
        c.setEventId("evt-c");
        c.setTimestamp(sameTime);
        c.setCreatedAt(Instant.parse("2026-03-01T00:00:00Z"));
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(a, b, c));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(3);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-b");
        assertThat(result.getItems().get(1).getEventId()).isEqualTo("evt-c");
        assertThat(result.getItems().get(2).getEventId()).isEqualTo("evt-a");
    }

    @Test
    void liveAlertsRedisTimestampNullFallsBackToCreatedAt() {
        V36LiveAlertSummaryDto noTs = new V36LiveAlertSummaryDto();
        noTs.setEventId("evt-no-ts");
        noTs.setTimestamp(null);
        noTs.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        V36LiveAlertSummaryDto withTs = new V36LiveAlertSummaryDto();
        withTs.setEventId("evt-with-ts");
        withTs.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(noTs, withTs));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-with-ts");
        assertThat(result.getItems().get(1).getEventId()).isEqualTo("evt-no-ts");
    }

    @Test
    void liveAlertsStablePaginationReturnsSameSortAcrossPages() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            V36LiveAlertSummaryDto alert = new V36LiveAlertSummaryDto();
            alert.setEventId("evt-" + i);
            alert.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", i + 1) + "T00:00:00Z"));
            allAlerts.add(alert);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);

        ApiPageResponse<V36LiveAlertSummaryDto> page1 = service.getLiveAlerts(
                null, null, null, null, null, null, 5, 0);
        ApiPageResponse<V36LiveAlertSummaryDto> page2 = service.getLiveAlerts(
                null, null, null, null, null, null, 5, 5);

        assertThat(page1.getItems()).hasSize(5);
        assertThat(page2.getItems()).hasSize(5);
        assertThat(page1.getItems().get(0).getEventId()).isEqualTo("evt-9");
        assertThat(page1.getItems().get(4).getEventId()).isEqualTo("evt-5");
        assertThat(page2.getItems().get(0).getEventId()).isEqualTo("evt-4");
        assertThat(page2.getItems().get(4).getEventId()).isEqualTo("evt-0");
    }

    @Test
    void criticalAlertsRedisSortedByTimestampDesc() {
        V36LiveAlertSummaryDto older = new V36LiveAlertSummaryDto();
        older.setEventId("evt-old");
        older.setTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        older.setRiskLevel("CRITICAL");
        V36LiveAlertSummaryDto newer = new V36LiveAlertSummaryDto();
        newer.setEventId("evt-new");
        newer.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        newer.setRiskLevel("CRITICAL");
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(older, newer));
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_CRITICAL_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getCriticalAlerts(100, 0);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-new");
        assertThat(result.getItems().get(1).getEventId()).isEqualTo("evt-old");
    }

    @Test
    void userAlertsRedisSortedByTimestampDesc() {
        V36LiveAlertSummaryDto older = new V36LiveAlertSummaryDto();
        older.setEventId("evt-old");
        older.setInsuredId("insured-1");
        older.setTimestamp(Instant.parse("2026-01-01T00:00:00Z"));
        V36LiveAlertSummaryDto newer = new V36LiveAlertSummaryDto();
        newer.setEventId("evt-new");
        newer.setInsuredId("insured-1");
        newer.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        when(redisReadService.readItems(eq(CacheKeys.userAlertsKey("insured-1")), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(older, newer));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getUserAlerts(
                "insured-1", null, null, null, 100, 0);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-new");
        assertThat(result.getItems().get(1).getEventId()).isEqualTo("evt-old");
    }

    @Test
    void redisPathPaginationPage1Metadata() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + i);
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", (i % 30) + 1) + "T00:00:00Z"));
            a.setRiskLevel("HIGH");
            allAlerts.add(a);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);

        assertThat(result.getItems()).hasSize(50);
        assertThat(result.getCount()).isEqualTo(150);
        assertThat(result.getHasMore()).isTrue();
    }

    @Test
    void redisPathPaginationPage2Metadata() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + i);
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", (i % 30) + 1) + "T00:00:00Z"));
            a.setRiskLevel("HIGH");
            allAlerts.add(a);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 50);

        assertThat(result.getItems()).hasSize(50);
        assertThat(result.getCount()).isEqualTo(150);
        assertThat(result.getHasMore()).isTrue();
    }

    @Test
    void redisPathPaginationLastPageMetadata() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + i);
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", (i % 30) + 1) + "T00:00:00Z"));
            a.setRiskLevel("HIGH");
            allAlerts.add(a);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 100);

        assertThat(result.getItems()).hasSize(50);
        assertThat(result.getCount()).isEqualTo(150);
        assertThat(result.getHasMore()).isFalse();
    }

    @Test
    void redisPathPaginationPartialLastPage() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 117; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + i);
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", (i % 30) + 1) + "T00:00:00Z"));
            a.setRiskLevel("HIGH");
            allAlerts.add(a);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 100);

        assertThat(result.getItems()).hasSize(17);
        assertThat(result.getCount()).isEqualTo(117);
        assertThat(result.getHasMore()).isFalse();
    }

    @Test
    void redisPathStablePaginationNoDuplicates() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 150; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + String.format("%03d", i));
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", (149 - i) % 30 + 1) + "T00:00:00Z"));
            a.setRiskLevel("HIGH");
            allAlerts.add(a);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);

        ApiPageResponse<V36LiveAlertSummaryDto> page1 = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);
        ApiPageResponse<V36LiveAlertSummaryDto> page2 = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 50);
        ApiPageResponse<V36LiveAlertSummaryDto> page3 = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 100);

        assertThat(page1.getItems()).hasSize(50);
        assertThat(page2.getItems()).hasSize(50);
        assertThat(page3.getItems()).hasSize(50);
        assertThat(page1.getCount()).isEqualTo(150);
        assertThat(page2.getCount()).isEqualTo(150);
        assertThat(page3.getCount()).isEqualTo(150);

        List<String> page1Ids = page1.getItems().stream().map(V36LiveAlertSummaryDto::getEventId).toList();
        List<String> page2Ids = page2.getItems().stream().map(V36LiveAlertSummaryDto::getEventId).toList();
        List<String> page3Ids = page3.getItems().stream().map(V36LiveAlertSummaryDto::getEventId).toList();

        assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids);
        assertThat(page1Ids).doesNotContainAnyElementsOf(page3Ids);
        assertThat(page2Ids).doesNotContainAnyElementsOf(page3Ids);
    }

    @Test
    void sqlPathPaginationPage1Metadata() {
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        List<AnomalyEvent> anomalyEvents = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            AnomalyEvent a = new AnomalyEvent();
            a.setId((long) i);
            a.setEventId("sql-evt-" + i);
            a.setRiskLevel("HIGH");
            a.setEventTime(Instant.parse("2026-06-" + String.format("%02d", (i % 30) + 1) + "T00:00:00Z"));
            a.setInsuredId("insured-1");
            anomalyEvents.add(a);
        }
        @SuppressWarnings("unchecked")
        Page<AnomalyEvent> page = mock(Page.class);
        when(page.getContent()).thenReturn(anomalyEvents);
        when(page.getTotalElements()).thenReturn(150L);
        when(page.hasNext()).thenReturn(true);
        when(anomalyEventRepository.searchV36Alerts(
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(page);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);

        assertThat(result.getItems()).hasSize(50);
        assertThat(result.getCount()).isEqualTo(150);
        assertThat(result.getHasMore()).isTrue();
    }

    @Test
    void sqlPathPaginationLastPageMetadata() {
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        List<AnomalyEvent> anomalyEvents = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            AnomalyEvent a = new AnomalyEvent();
            a.setId((long) i);
            a.setEventId("sql-evt-" + i);
            a.setRiskLevel("HIGH");
            a.setEventTime(Instant.parse("2026-06-" + String.format("%02d", (i % 30) + 1) + "T00:00:00Z"));
            a.setInsuredId("insured-1");
            anomalyEvents.add(a);
        }
        @SuppressWarnings("unchecked")
        Page<AnomalyEvent> page = mock(Page.class);
        when(page.getContent()).thenReturn(anomalyEvents);
        when(page.getTotalElements()).thenReturn(150L);
        when(page.hasNext()).thenReturn(false);
        when(anomalyEventRepository.searchV36Alerts(
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(page);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 100);

        assertThat(result.getItems()).hasSize(50);
        assertThat(result.getCount()).isEqualTo(150);
        assertThat(result.getHasMore()).isFalse();
    }

    @Test
    void globalSortBeforePaginationNewestOnPage1() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        V36LiveAlertSummaryDto newest = new V36LiveAlertSummaryDto();
        newest.setEventId("evt-newest");
        newest.setTimestamp(Instant.parse("2026-06-30T00:00:00Z"));
        newest.setRiskLevel("HIGH");
        allAlerts.add(newest);
        for (int i = 0; i < 149; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + i);
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", (i % 29) + 1) + "T00:00:00Z"));
            a.setRiskLevel("HIGH");
            allAlerts.add(a);
        }
        java.util.Collections.shuffle(allAlerts);
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);

        assertThat(result.getItems()).isNotEmpty();
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-newest");
        assertThat(result.getCount()).isEqualTo(150);
        assertThat(result.getHasMore()).isTrue();
    }

    @Test
    void globalSortWithFilterRespectsPaginationMetadata() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + i);
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", (i % 30) + 1) + "T00:00:00Z"));
            a.setRiskLevel(i < 80 ? "HIGH" : "LOW");
            a.setInsuredId("insured-1");
            allAlerts.add(a);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                "HIGH", null, null, null, null, null, 20, 40);

        assertThat(result.getItems()).hasSize(20);
        assertThat(result.getCount()).isEqualTo(80);
        assertThat(result.getHasMore()).isTrue();
    }

    @Test
    void criticalEndpointEqualsFilteredLive() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + i);
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", i + 1) + "T00:00:00Z"));
            a.setRiskLevel(i < 4 ? "CRITICAL" : "HIGH");
            allAlerts.add(a);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_CRITICAL_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ApiPageResponse<V36LiveAlertSummaryDto> criticalResult = service.getCriticalAlerts(50, 0);
        ApiPageResponse<V36LiveAlertSummaryDto> filteredLiveResult = service.getLiveAlerts(
                "CRITICAL", null, null, null, null, null, 50, 0);

        assertThat(criticalResult.getItems()).hasSize(4);
        assertThat(filteredLiveResult.getItems()).hasSize(4);
        assertThat(criticalResult.getCount()).isEqualTo(filteredLiveResult.getCount());
        assertThat(criticalResult.getHasMore()).isEqualTo(filteredLiveResult.getHasMore());
        List<String> criticalIds = criticalResult.getItems().stream().map(V36LiveAlertSummaryDto::getEventId).toList();
        List<String> filteredIds = filteredLiveResult.getItems().stream().map(V36LiveAlertSummaryDto::getEventId).toList();
        assertThat(criticalIds).containsExactlyElementsOf(filteredIds);
    }

    @Test
    void criticalAlertsAreSubsetOfLiveAlerts() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + i);
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", i + 1) + "T00:00:00Z"));
            a.setRiskLevel(i < 4 ? "CRITICAL" : "HIGH");
            allAlerts.add(a);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_CRITICAL_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ApiPageResponse<V36LiveAlertSummaryDto> criticalResult = service.getCriticalAlerts(50, 0);
        ApiPageResponse<V36LiveAlertSummaryDto> liveResult = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);

        Set<String> liveIds = liveResult.getItems().stream().map(V36LiveAlertSummaryDto::getEventId)
                .collect(Collectors.toSet());
        for (V36LiveAlertSummaryDto critical : criticalResult.getItems()) {
            assertThat(liveIds).contains(critical.getEventId());
        }
    }

    @Test
    void divergentRedisLiveAndCriticalSourcesMerged() {
        V36LiveAlertSummaryDto liveOnly = new V36LiveAlertSummaryDto();
        liveOnly.setEventId("evt-live-only");
        liveOnly.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        liveOnly.setRiskLevel("HIGH");

        V36LiveAlertSummaryDto both = new V36LiveAlertSummaryDto();
        both.setEventId("evt-both");
        both.setTimestamp(Instant.parse("2026-06-02T00:00:00Z"));
        both.setRiskLevel("CRITICAL");

        V36LiveAlertSummaryDto criticalOnly = new V36LiveAlertSummaryDto();
        criticalOnly.setEventId("evt-critical-only");
        criticalOnly.setTimestamp(Instant.parse("2026-06-03T00:00:00Z"));
        criticalOnly.setRiskLevel("CRITICAL");

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(liveOnly, both));
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_CRITICAL_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(both, criticalOnly));

        ApiPageResponse<V36LiveAlertSummaryDto> liveResult = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);
        ApiPageResponse<V36LiveAlertSummaryDto> criticalResult = service.getCriticalAlerts(50, 0);

        assertThat(liveResult.getItems()).hasSize(3);
        Set<String> liveIds = liveResult.getItems().stream().map(V36LiveAlertSummaryDto::getEventId)
                .collect(Collectors.toSet());
        assertThat(liveIds).contains("evt-live-only", "evt-both", "evt-critical-only");

        assertThat(criticalResult.getItems()).hasSize(2);
        Set<String> criticalIds = criticalResult.getItems().stream().map(V36LiveAlertSummaryDto::getEventId)
                .collect(Collectors.toSet());
        assertThat(criticalIds).contains("evt-both", "evt-critical-only");
    }

    @Test
    void criticalPaginationAfterFiltering() {
        List<V36LiveAlertSummaryDto> allAlerts = new java.util.ArrayList<>();
        for (int i = 0; i < 27; i++) {
            V36LiveAlertSummaryDto a = new V36LiveAlertSummaryDto();
            a.setEventId("evt-" + i);
            a.setTimestamp(Instant.parse("2026-06-" + String.format("%02d", (i % 27) + 1) + "T00:00:00Z"));
            a.setRiskLevel("CRITICAL");
            allAlerts.add(a);
        }
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(allAlerts);
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_CRITICAL_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());

        ApiPageResponse<V36LiveAlertSummaryDto> page1 = service.getCriticalAlerts(20, 0);
        ApiPageResponse<V36LiveAlertSummaryDto> page2 = service.getCriticalAlerts(20, 20);

        assertThat(page1.getItems()).hasSize(20);
        assertThat(page1.getCount()).isEqualTo(27);
        assertThat(page1.getHasMore()).isTrue();

        assertThat(page2.getItems()).hasSize(7);
        assertThat(page2.getCount()).isEqualTo(27);
        assertThat(page2.getHasMore()).isFalse();
    }

    @Test
    void criticalSqlFallbackConsistentWithLiveSqlFallback() {
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_CRITICAL_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        List<AnomalyEvent> criticalEvents = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            AnomalyEvent a = new AnomalyEvent();
            a.setId((long) i);
            a.setEventId("sql-crit-evt-" + i);
            a.setRiskLevel("CRITICAL");
            a.setEventTime(Instant.parse("2026-06-" + String.format("%02d", i + 1) + "T00:00:00Z"));
            a.setInsuredId("insured-1");
            criticalEvents.add(a);
        }
        @SuppressWarnings("unchecked")
        Page<AnomalyEvent> page = mock(Page.class);
        when(page.getContent()).thenReturn(criticalEvents);
        when(page.getTotalElements()).thenReturn(4L);
        when(page.hasNext()).thenReturn(false);
        when(anomalyEventRepository.searchV36Alerts(
                eq("CRITICAL"), any(), any(), any(), any(), any(), any()))
                .thenReturn(page);

        ApiPageResponse<V36LiveAlertSummaryDto> criticalResult = service.getCriticalAlerts(50, 0);

        assertThat(criticalResult.getItems()).hasSize(4);
        assertThat(criticalResult.getCount()).isEqualTo(4);
    }

    @Test
    void noDuplicateEventIdsAcrossLiveAndCriticalSources() {
        V36LiveAlertSummaryDto alert = new V36LiveAlertSummaryDto();
        alert.setEventId("evt-duplicate");
        alert.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        alert.setRiskLevel("CRITICAL");

        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(alert));
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_CRITICAL_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(alert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-duplicate");
    }

    @Test
    void canonicalZsetReadReturnsAlertsWithSourceRedisZset() {
        V36LiveAlertSummaryDto alert = new V36LiveAlertSummaryDto();
        alert.setEventId("evt-zset");
        alert.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        alert.setRiskLevel("HIGH");
        when(redisReadService.readZSetAlertItems(
                eq(CacheKeys.ALERTS_LIVE_V36_ZSET),
                eq(CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX),
                eq(V36LiveAlertSummaryDto.class),
                anyInt()))
                .thenReturn(List.of(alert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-zset");
        assertThat(result.getItems().get(0).getSource()).isEqualTo("redis_zset");
    }

    @Test
    void canonicalZsetPreferredOverLegacyList() {
        V36LiveAlertSummaryDto zsetAlert = new V36LiveAlertSummaryDto();
        zsetAlert.setEventId("evt-zset-fresh");
        zsetAlert.setTimestamp(Instant.parse("2026-06-02T00:00:00Z"));
        zsetAlert.setRiskLevel("HIGH");

        V36LiveAlertSummaryDto legacyAlert = new V36LiveAlertSummaryDto();
        legacyAlert.setEventId("evt-legacy-stale");
        legacyAlert.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        legacyAlert.setRiskLevel("HIGH");

        when(redisReadService.readZSetAlertItems(
                eq(CacheKeys.ALERTS_LIVE_V36_ZSET),
                eq(CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX),
                eq(V36LiveAlertSummaryDto.class),
                anyInt()))
                .thenReturn(List.of(zsetAlert));
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(legacyAlert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-zset-fresh");
        assertThat(result.getItems().get(0).getSource()).isEqualTo("redis_zset");
    }

    @Test
    void legacyListFallbackWhenZsetEmpty() {
        V36LiveAlertSummaryDto legacyAlert = new V36LiveAlertSummaryDto();
        legacyAlert.setEventId("evt-legacy");
        legacyAlert.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        legacyAlert.setRiskLevel("HIGH");

        when(redisReadService.readZSetAlertItems(
                eq(CacheKeys.ALERTS_LIVE_V36_ZSET),
                eq(CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX),
                eq(V36LiveAlertSummaryDto.class),
                anyInt()))
                .thenReturn(List.of());
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of(legacyAlert));

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getEventId()).isEqualTo("evt-legacy");
        assertThat(result.getItems().get(0).getSource()).isEqualTo("redis_legacy_list");
        assertThat(result.getItems().get(0).getWarnings()).contains("canonical_zset_empty_legacy_list_used");
    }

    @Test
    void criticalFromZsetSubsetOfLiveFromZset() {
        V36LiveAlertSummaryDto liveAlert = new V36LiveAlertSummaryDto();
        liveAlert.setEventId("evt-live");
        liveAlert.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        liveAlert.setRiskLevel("HIGH");

        V36LiveAlertSummaryDto criticalAlert = new V36LiveAlertSummaryDto();
        criticalAlert.setEventId("evt-critical");
        criticalAlert.setTimestamp(Instant.parse("2026-06-02T00:00:00Z"));
        criticalAlert.setRiskLevel("CRITICAL");

        when(redisReadService.readZSetAlertItems(
                eq(CacheKeys.ALERTS_LIVE_V36_ZSET),
                eq(CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX),
                eq(V36LiveAlertSummaryDto.class),
                anyInt()))
                .thenReturn(List.of(liveAlert, criticalAlert));
        when(redisReadService.readZSetAlertItems(
                eq(CacheKeys.ALERTS_CRITICAL_V36_ZSET),
                eq(CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX),
                eq(V36LiveAlertSummaryDto.class),
                anyInt()))
                .thenReturn(List.of());

        ApiPageResponse<V36LiveAlertSummaryDto> liveResult = service.getLiveAlerts(
                "CRITICAL", null, null, null, null, null, 50, 0);
        ApiPageResponse<V36LiveAlertSummaryDto> criticalResult = service.getCriticalAlerts(50, 0);

        assertThat(liveResult.getItems()).hasSize(1);
        assertThat(criticalResult.getItems()).hasSize(1);
        assertThat(liveResult.getItems().get(0).getEventId()).isEqualTo("evt-critical");
        assertThat(criticalResult.getItems().get(0).getEventId()).isEqualTo("evt-critical");
    }

    @Test
    void zsetReadHandlesMissingPayloadGracefully() {
        when(redisReadService.readZSetAlertItems(
                eq(CacheKeys.ALERTS_LIVE_V36_ZSET),
                eq(CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX),
                eq(V36LiveAlertSummaryDto.class),
                anyInt()))
                .thenReturn(List.of());

        V36LiveAlertSummaryDto sqlAlert = new V36LiveAlertSummaryDto();
        sqlAlert.setEventId("evt-sql");
        sqlAlert.setTimestamp(Instant.parse("2026-06-01T00:00:00Z"));
        sqlAlert.setRiskLevel("HIGH");
        when(redisReadService.readItems(eq(CacheKeys.ALERTS_LIVE_V36), eq(V36LiveAlertSummaryDto.class), anyInt()))
                .thenReturn(List.of());
        when(snapshotFallbackService.readListFromSql(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        List<AnomalyEvent> events = List.of(anomaly("evt-sql", "HIGH", Instant.parse("2026-06-01T00:00:00Z")));
        @SuppressWarnings("unchecked")
        Page<AnomalyEvent> page = mock(Page.class);
        when(page.getContent()).thenReturn(events);
        when(page.getTotalElements()).thenReturn(1L);
        when(page.hasNext()).thenReturn(false);
        when(anomalyEventRepository.searchV36Alerts(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(page);

        ApiPageResponse<V36LiveAlertSummaryDto> result = service.getLiveAlerts(
                null, null, null, null, null, null, 50, 0);

        assertThat(result.getItems()).hasSize(1);
    }

    @Test
    void alertDetailEvidenceContainsDeviationWithPreviousPrediction() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-previous");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-1");
        redisDetail.setSource("redis");

        V36NextEventPredictionDto prediction = new V36NextEventPredictionDto();
        prediction.setSessionId("sess-1");
        prediction.setContextEventId("evt-before");
        prediction.setHeads(Map.of("api_family", List.of(new V36NextEventPredictionHeadItemDto("documents", 0.63, 1))));

        Map<String, Object> previousPrediction = new LinkedHashMap<>();
        previousPrediction.put("contextEventId", "evt-earlier");
        previousPrediction.put("heads", Map.of("api_family", List.of(Map.of("value", "help", "probability", 0.59, "rank", 1))));

        V36NextEventPredictionDeviationDto deviation = new V36NextEventPredictionDeviationDto();
        deviation.setActual(Map.of("api_family", "auth"));
        deviation.setDeviationScore(0.893);
        deviation.setPreviousPrediction(previousPrediction);
        deviation.setPreviousPredictionContextEventId("evt-earlier");
        deviation.setEvaluatedEventId("evt-previous");
        prediction.setDeviation(deviation);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-previous"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(nextEventPredictionService.getPredictionByContextEventId("sess-1", "evt-previous"))
                .thenReturn(prediction);

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-previous");

        assertThat(result.getNextEventPredictionEvidence()).isNotNull();
        assertThat(result.getNextEventPredictionEvidence()).containsKey("prediction");
        assertThat(result.getNextEventPredictionEvidence()).containsKey("deviation");

        Object rawDeviation = result.getNextEventPredictionEvidence().get("deviation");
        assertThat(rawDeviation).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> deviationMap = (Map<String, Object>) rawDeviation;
        assertThat(deviationMap).containsKey("previousPrediction");
        assertThat(deviationMap.get("previousPrediction")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> pp = (Map<String, Object>) deviationMap.get("previousPrediction");
        assertThat(pp).containsEntry("contextEventId", "evt-earlier");
        assertThat(deviationMap).containsEntry("previousPredictionContextEventId", "evt-earlier");
        assertThat(deviationMap).containsEntry("evaluatedEventId", "evt-previous");
        assertThat(deviationMap).containsEntry("deviationScore", 0.893);
    }

    @Test
    void alertDetailEvidenceSurvivesMissingPreviousPrediction() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-no-pp");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-1");
        redisDetail.setSource("redis");

        V36NextEventPredictionDto prediction = new V36NextEventPredictionDto();
        prediction.setSessionId("sess-1");
        prediction.setContextEventId("evt-no-pp");
        prediction.setHeads(Map.of("api_family", List.of(new V36NextEventPredictionHeadItemDto("documents", 0.63, 1))));

        V36NextEventPredictionDeviationDto deviation = new V36NextEventPredictionDeviationDto();
        deviation.setActual(Map.of("api_family", "auth"));
        deviation.setDeviationScore(0.893);
        deviation.setPreviousPrediction(null);
        prediction.setDeviation(deviation);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-no-pp"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(nextEventPredictionService.getPredictionByContextEventId("sess-1", "evt-no-pp"))
                .thenReturn(prediction);

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-no-pp");

        assertThat(result.getNextEventPredictionEvidence()).isNotNull();
        Object rawDeviation = result.getNextEventPredictionEvidence().get("deviation");
        assertThat(rawDeviation).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> deviationMap = (Map<String, Object>) rawDeviation;
        assertThat(deviationMap.get("previousPrediction")).isNull();
        assertThat(deviationMap).containsEntry("deviationScore", 0.893);
    }

    @Test
    void alertDetailEvidenceDoesNotChangeRiskScore() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-no-risk-change");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-1");
        redisDetail.setSource("redis");
        redisDetail.setFinalRiskScore(72.5);

        V36NextEventPredictionDto prediction = new V36NextEventPredictionDto();
        prediction.setSessionId("sess-1");
        prediction.setContextEventId("evt-no-risk-change");
        prediction.setHeads(Map.of("api_family", List.of(new V36NextEventPredictionHeadItemDto("documents", 0.63, 1))));

        V36NextEventPredictionDeviationDto deviation = new V36NextEventPredictionDeviationDto();
        deviation.setActual(Map.of("api_family", "auth"));
        deviation.setDeviationScore(0.893);
        prediction.setDeviation(deviation);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-no-risk-change"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(nextEventPredictionService.getPredictionByContextEventId("sess-1", "evt-no-risk-change"))
                .thenReturn(prediction);

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-no-risk-change");

        assertThat(result.getFinalRiskScore()).isEqualTo(72.5);
        assertThat(result.getNextEventPredictionEvidence()).isNotNull();
    }

    @Test
    void rawPayloadMismatchDropped() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String mismatchedPayload = mapper.writeValueAsString(Map.of(
                "eventId", "evt-776",
                "finalRiskScore", 85.63,
                "anomalyType", "api_scraping"
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(300L);
        anomaly.setEventId("evt-752");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setInvestigationPayloadJson(mismatchedPayload);
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-752"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-752"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-752");

        assertThat(result.getRawPayload()).isNull();
        assertThat(result.getWarnings()).contains("raw_payload_event_mismatch");
        assertThat(result.getEventId()).isEqualTo("evt-752");
    }

    @Test
    void attributionMismatchDropped() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String evidenceJson = mapper.writeValueAsString(Map.of(
                "finalRiskScore", 85.63,
                "apiTemplate", "/requests/messages/file"
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(400L);
        anomaly.setEventId("evt-attribution-mismatch");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setAnomalyTypeConfidence(0.55);
        anomaly.setAnomalyTypeSource("hybrid_rules_models");
        anomaly.setAnomalyTypeEvidenceJson(evidenceJson);
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-attribution-mismatch"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-attribution-mismatch"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-attribution-mismatch");

        assertThat(result.getAnomalyTypeAttribution()).isNotNull();
        assertThat(result.getAnomalyTypeAttribution().getEvidence()).isNull();
        assertThat(result.getWarnings()).contains("anomaly_type_attribution_event_mismatch");
        assertThat(result.getAnomalyTypeAttribution().getAnomalyType()).isEqualTo("unknown_suspicious_behavior");
        assertThat(result.getAnomalyTypeAttribution().getConfidence()).isEqualTo(0.55);
        assertThat(result.getAnomalyTypeAttribution().getSource()).isEqualTo("hybrid_rules_models");
    }

    @Test
    void exactPayloadUnavailableSafeMode() {
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(500L);
        anomaly.setEventId("evt-safe-mode");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-safe-mode"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-safe-mode"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-safe-mode");

        assertThat(result.getSource()).isEqualTo("sql");
        assertThat(result.getWarnings()).contains("exact_event_payload_unavailable");
        assertThat(result.getRawPayload()).isNull();
        assertThat(result.getSequenceEvidence()).isNull();
        assertThat(result.getTabularEvidence()).isNull();
        assertThat(result.getForecastContext()).isNull();
        assertThat(result.getEventMetadata()).isNull();
        assertThat(result.getEventId()).isEqualTo("evt-safe-mode");
        assertThat(result.getInsuredId()).isEqualTo("insured-1");
        assertThat(result.getFinalRiskScore()).isEqualTo(63.53);
        assertThat(result.getAnomalyType()).isEqualTo("unknown_suspicious_behavior");
    }

    @Test
    void exactPayloadMatchAllowed() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Map<String, Object> rawPayload = Map.of("testKey", "testValue");
        String matchingPayload = mapper.writeValueAsString(Map.of(
                "eventId", "evt-exact-match",
                "finalRiskScore", 63.53,
                "anomalyType", "unknown_suspicious_behavior",
                "rawPayload", rawPayload
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(600L);
        anomaly.setEventId("evt-exact-match");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setInvestigationPayloadJson(matchingPayload);
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-exact-match"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-exact-match"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-exact-match");

        assertThat(result.getRawPayload()).isNotNull();
        assertThat(result.getRawPayload()).containsEntry("testKey", "testValue");
        assertThat(result.getSource()).isEqualTo("sql-payload");
        assertThat(result.getWarnings()).doesNotContain("raw_payload_event_mismatch");
    }

    @Test
    void redisExactPayloadHydratesSqlFallback() {
        Instant eventTime = Instant.parse("2026-05-25T02:15:00Z");

        V36LiveAlertSummaryDto livePayload = new V36LiveAlertSummaryDto();
        livePayload.setEventId("evt-redis-enrich");
        livePayload.setRecordId("evt-redis-enrich");
        livePayload.setEventAction("download_request_doc");
        livePayload.setApiTemplate("/requests/messages/file");
        livePayload.setApiFamily("requests");
        livePayload.setController("RequestsController");
        livePayload.setPage("requests");
        livePayload.setCountry("NG");
        livePayload.setDevice("desktop");
        livePayload.setBrowser("chrome");
        livePayload.setOs("windows");
        livePayload.setHttpMethod("GET");
        livePayload.setStatus("SUCCESS");
        livePayload.setTimestamp(eventTime);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(700L);
        anomaly.setEventId("evt-redis-enrich");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(eventTime);
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-redis-enrich"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(redisReadService.readValue(CacheKeys.liveAlertPayloadKey("evt-redis-enrich"), V36LiveAlertSummaryDto.class))
                .thenReturn(Optional.of(livePayload));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-redis-enrich"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-redis-enrich");

        assertThat(result.getSource()).isEqualTo("sql+redis-payload");
        assertThat(result.getEventMetadata()).isNotNull();
        assertThat(result.getEventMetadata()).containsEntry("eventId", "evt-redis-enrich");
        assertThat(result.getEventMetadata()).containsEntry("eventAction", "download_request_doc");
        assertThat(result.getEventMetadata()).containsEntry("apiTemplate", "/requests/messages/file");
        assertThat(result.getEventMetadata()).containsEntry("apiFamily", "requests");
        assertThat(result.getEventMetadata()).containsEntry("controller", "RequestsController");
        assertThat(result.getEventMetadata()).containsEntry("page", "requests");
        assertThat(result.getEventMetadata()).containsEntry("country", "NG");
        assertThat(result.getEventMetadata()).containsEntry("device", "desktop");
        assertThat(result.getEventMetadata()).containsEntry("browser", "chrome");
        assertThat(result.getEventMetadata()).containsEntry("os", "windows");
        assertThat(result.getEventMetadata()).containsEntry("httpMethod", "GET");
        assertThat(result.getEventMetadata()).containsEntry("status", "SUCCESS");
        assertThat(result.getWarnings()).contains("redis_event_payload_used");
        assertThat(result.getWarnings()).doesNotContain("exact_event_payload_unavailable");
        assertThat(result.getRawPayload()).isNull();
    }

    @Test
    void redisPayloadMismatchRejected() {
        V36LiveAlertSummaryDto livePayload = new V36LiveAlertSummaryDto();
        livePayload.setEventId("other-event");
        livePayload.setRecordId("other-event");
        livePayload.setEventAction("some_action");

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(800L);
        anomaly.setEventId("evt-redis-mismatch");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-redis-mismatch"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(redisReadService.readValue(CacheKeys.liveAlertPayloadKey("evt-redis-mismatch"), V36LiveAlertSummaryDto.class))
                .thenReturn(Optional.of(livePayload));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-redis-mismatch"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-redis-mismatch");

        assertThat(result.getSource()).isEqualTo("sql");
        assertThat(result.getWarnings()).contains("redis_event_payload_mismatch");
        assertThat(result.getWarnings()).contains("exact_event_payload_unavailable");
    }

    @Test
    void redisPayloadMissingSafeMode() {
        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(900L);
        anomaly.setEventId("evt-redis-missing");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-redis-missing"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-redis-missing"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-redis-missing");

        assertThat(result.getSource()).isEqualTo("sql");
        assertThat(result.getWarnings()).contains("exact_event_payload_unavailable");
        assertThat(result.getRawPayload()).isNull();
        assertThat(result.getSequenceEvidence()).isNull();
    }

    @Test
    void timestampFallbackFromRedis() {
        Instant redisTimestamp = Instant.parse("2026-06-25T16:01:26.140Z");

        V36LiveAlertSummaryDto livePayload = new V36LiveAlertSummaryDto();
        livePayload.setEventId("evt-ts-fallback");
        livePayload.setRecordId("evt-ts-fallback");
        livePayload.setEventAction("test_action");
        livePayload.setTimestamp(redisTimestamp);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1000L);
        anomaly.setEventId("evt-ts-fallback");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-ts-fallback"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(redisReadService.readValue(CacheKeys.liveAlertPayloadKey("evt-ts-fallback"), V36LiveAlertSummaryDto.class))
                .thenReturn(Optional.of(livePayload));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-ts-fallback"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-ts-fallback");

        assertThat(result.getTimestamp()).isEqualTo(redisTimestamp);
        assertThat(result.getSource()).isEqualTo("sql+redis-payload");
        assertThat(result.getWarnings()).contains("redis_event_payload_used");
    }

    @Test
    void sameContextDeviationRemoved() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-same-context");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-1");
        redisDetail.setSource("redis");

        V36NextEventPredictionDto prediction = new V36NextEventPredictionDto();
        prediction.setSessionId("sess-1");
        prediction.setContextEventId("evt-before");
        prediction.setHeads(Map.of("api_family", List.of(new V36NextEventPredictionHeadItemDto("documents", 0.63, 1))));

        Map<String, Object> previousPrediction = new LinkedHashMap<>();
        previousPrediction.put("contextEventId", "evt-earlier");

        V36NextEventPredictionDeviationDto deviation = new V36NextEventPredictionDeviationDto();
        deviation.setActual(Map.of("api_family", "auth"));
        deviation.setDeviationScore(0.893);
        deviation.setPreviousPrediction(previousPrediction);
        deviation.setPreviousPredictionContextEventId("evt-same-context");
        deviation.setEvaluatedEventId("evt-same-context");
        prediction.setDeviation(deviation);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-same-context"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(nextEventPredictionService.getPredictionByContextEventId("sess-1", "evt-same-context"))
                .thenReturn(prediction);

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-same-context");

        assertThat(result.getNextEventPredictionEvidence()).isNotNull();
        assertThat(result.getNextEventPredictionEvidence()).containsKey("prediction");
        assertThat(result.getNextEventPredictionEvidence()).doesNotContainKey("deviation");
        assertThat(result.getWarnings()).contains("next_event_prediction_same_context_deviation_ignored");
    }

    @Test
    void sameContextDeviationRemovedViaPreviousPrediction() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-same-ctx-nested");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-1");
        redisDetail.setSource("redis");

        V36NextEventPredictionDto prediction = new V36NextEventPredictionDto();
        prediction.setSessionId("sess-1");
        prediction.setContextEventId("evt-before");
        prediction.setHeads(Map.of("api_family", List.of(new V36NextEventPredictionHeadItemDto("documents", 0.63, 1))));

        Map<String, Object> previousPrediction = new LinkedHashMap<>();
        previousPrediction.put("contextEventId", "evt-same-ctx-nested");

        V36NextEventPredictionDeviationDto deviation = new V36NextEventPredictionDeviationDto();
        deviation.setActual(Map.of("api_family", "auth"));
        deviation.setDeviationScore(0.893);
        deviation.setPreviousPrediction(previousPrediction);
        deviation.setPreviousPredictionContextEventId("evt-earlier");
        deviation.setEvaluatedEventId("evt-same-ctx-nested");
        prediction.setDeviation(deviation);

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-same-ctx-nested"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(nextEventPredictionService.getPredictionByContextEventId("sess-1", "evt-same-ctx-nested"))
                .thenReturn(prediction);

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-same-ctx-nested");

        assertThat(result.getNextEventPredictionEvidence()).isNotNull();
        assertThat(result.getNextEventPredictionEvidence()).containsKey("prediction");
        assertThat(result.getNextEventPredictionEvidence()).doesNotContainKey("deviation");
        assertThat(result.getWarnings()).contains("next_event_prediction_same_context_deviation_ignored");
    }

    @Test
    void realMismatchShapeRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String mismatchedPayload = mapper.writeValueAsString(Map.of(
                "eventId", "anom-000000004393",
                "eventMetadata", Map.of(
                        "eventId", "anom-000000004402",
                        "eventAction", "Déconnexion",
                        "apiTemplate", "/auth/logout",
                        "apiFamily", "auth"
                )
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1100L);
        anomaly.setEventId("anom-000000004393");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setInvestigationPayloadJson(mismatchedPayload);
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("anom-000000004393"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("anom-000000004393"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("anom-000000004393");

        assertThat(result.getSource()).isNotEqualTo("sql-payload");
        assertThat(result.getWarnings()).contains("sql_payload_event_mismatch");
        assertThat(result.getEventMetadata()).isNull();
        assertThat(result.getEventId()).isEqualTo("anom-000000004393");
    }

    @Test
    void redisInvestigationMismatchRejected() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-redis-meta-mismatch");
        redisDetail.setRecordId("evt-redis-meta-mismatch");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-1");
        redisDetail.setEventMetadata(Map.of("eventId", "other-event"));
        redisDetail.setSource("redis");

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1450L);
        anomaly.setEventId("evt-redis-meta-mismatch");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-redis-meta-mismatch"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-redis-meta-mismatch"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-redis-meta-mismatch");

        assertThat(result.getSource()).isEqualTo("sql");
        assertThat(result.getEventMetadata()).isNull();
    }

    @Test
    void sessionPayloadEventMetadataMismatchRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String mismatchedPayload = mapper.writeValueAsString(Map.of(
                "eventId", "evt-sess-meta-mismatch",
                "eventMetadata", Map.of("eventId", "wrong-event"),
                "finalRiskScore", 63.53,
                "anomalyType", "unknown_suspicious_behavior"
        ));

        SessionAnalysis session = new SessionAnalysis();
        session.setSessionId("sess-1");
        session.setInvestigationPayloadJson(mismatchedPayload);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1200L);
        anomaly.setEventId("evt-sess-meta-mismatch");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-sess-meta-mismatch"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-sess-meta-mismatch"))
                .thenReturn(Optional.of(anomaly));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-1"))
                .thenReturn(Optional.of(session));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-sess-meta-mismatch");

        assertThat(result.getSource()).isEqualTo("sql");
        assertThat(result.getWarnings()).contains("sql_payload_event_mismatch");
        assertThat(result.getRawPayload()).isNull();
        assertThat(result.getSequenceEvidence()).isNull();
    }

    @Test
    void redisExactFallbackAfterPayloadMismatch() {
        Instant eventTime = Instant.parse("2026-05-25T02:15:00Z");

        V36LiveAlertSummaryDto livePayload = new V36LiveAlertSummaryDto();
        livePayload.setEventId("evt-redis-fallback");
        livePayload.setRecordId("evt-redis-fallback");
        livePayload.setEventAction("download_file");
        livePayload.setApiTemplate("/files/download");
        livePayload.setApiFamily("files");
        livePayload.setStatus("SUCCESS");

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1400L);
        anomaly.setEventId("evt-redis-fallback");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(eventTime);
        anomaly.setInvestigationPayloadJson("{\"eventId\":\"evt-redis-fallback\",\"eventMetadata\":{\"eventId\":\"wrong-event\"}}");
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-redis-fallback"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(redisReadService.readValue(CacheKeys.liveAlertPayloadKey("evt-redis-fallback"), V36LiveAlertSummaryDto.class))
                .thenReturn(Optional.of(livePayload));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-redis-fallback"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-redis-fallback");

        assertThat(result.getSource()).isEqualTo("sql+redis-payload");
        assertThat(result.getWarnings()).contains("sql_payload_event_mismatch");
        assertThat(result.getWarnings()).contains("redis_event_payload_used");
        assertThat(result.getEventMetadata()).isNotNull();
        assertThat(result.getEventMetadata()).containsEntry("eventId", "evt-redis-fallback");
        assertThat(result.getEventMetadata()).containsEntry("eventAction", "download_file");
    }

    @Test
    void sessionLifecycleFromSessionAnalysis() {
        SessionAnalysis session = new SessionAnalysis();
        session.setSessionId("sess-lifecycle");
        session.setSessionDurationSeconds(900L);
        session.setTotalEvents(8);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1500L);
        anomaly.setEventId("evt-lifecycle-sa");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-lifecycle");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-lifecycle-sa"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-lifecycle-sa"))
                .thenReturn(Optional.of(anomaly));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-lifecycle"))
                .thenReturn(Optional.of(session));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-lifecycle-sa");

        assertThat(result.getSessionDurationMs()).isEqualTo(900000L);
        assertThat(result.getSessionEventCount()).isEqualTo(8);
    }

    @Test
    void validPayloadAccepted() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String validPayload = mapper.writeValueAsString(Map.of(
                "eventId", "anom-000000004393",
                "eventMetadata", Map.of("eventId", "anom-000000004393"),
                "finalRiskScore", 63.53,
                "anomalyType", "unknown_suspicious_behavior",
                "rawPayload", Map.of("key", "value")
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1700L);
        anomaly.setEventId("anom-000000004393");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setInvestigationPayloadJson(validPayload);
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("anom-000000004393"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("anom-000000004393"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("anom-000000004393");

        assertThat(result.getSource()).isEqualTo("sql-payload");
        assertThat(result.getEventMetadata()).isNotNull();
        assertThat(result.getEventMetadata()).containsEntry("eventId", "anom-000000004393");
        assertThat(result.getRawPayload()).isNotNull();
    }

    @Test
    void eventJsonSessionSummaryMetadataSuppressed() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String eventJson = mapper.writeValueAsString(Map.of(
                "lastEventId", "anom-000000004402",
                "lastAction", "Déconnexion",
                "page", "logout",
                "status", "SUCCESS"
        ));

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1800L);
        anomaly.setEventId("anom-000000004393");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-1");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setEventJson(eventJson);
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(any(), eq(V36AlertInvestigationDetailDto.class)))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("anom-000000004393"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("anom-000000004393");

        assertThat(result.getEventMetadata()).isNull();
        assertThat(result.getSource()).isEqualTo("sql");
    }

    @Test
    void redisSourceLifecycleEnrichmentOverridesStale() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-lifecycle-redis");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-lifecycle");
        redisDetail.setSource("redis");
        redisDetail.setSessionDurationMs(1000L);
        redisDetail.setSessionEventCount(4);
        redisDetail.setSessionEndReason(null);

        SessionAnalysis session = new SessionAnalysis();
        session.setSessionId("sess-lifecycle");
        session.setSessionDurationSeconds(15L);
        session.setTotalEvents(13);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1900L);
        anomaly.setEventId("evt-lifecycle-redis");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-lifecycle");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-lifecycle-redis"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-lifecycle-redis"))
                .thenReturn(Optional.of(anomaly));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-lifecycle"))
                .thenReturn(Optional.of(session));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-lifecycle-redis");

        assertThat(result.getSessionDurationMs()).isEqualTo(15000L);
        assertThat(result.getSessionEventCount()).isEqualTo(13);
        assertThat(result.getSource()).isEqualTo("redis");
    }

    @Test
    void sessionAnalysisPayloadLifecycleParsed() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-sa-payload-lifecycle");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-sa-payload");
        redisDetail.setSource("redis");

        SessionAnalysis session = new SessionAnalysis();
        session.setSessionId("sess-sa-payload");
        session.setSessionDurationSeconds(null);
        session.setTotalEvents(null);
        session.setInvestigationPayloadJson("{\"sessionEndReason\":\"timeout\",\"sessionEndedExplicitly\":true,\"sessionDurationMs\":20000,\"sessionEventCount\":20}");

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1910L);
        anomaly.setEventId("evt-sa-payload-lifecycle");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-sa-payload");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-sa-payload-lifecycle"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-sa-payload-lifecycle"))
                .thenReturn(Optional.of(anomaly));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-sa-payload"))
                .thenReturn(Optional.of(session));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-sa-payload-lifecycle");

        assertThat(result.getSessionEndReason()).isEqualTo("timeout");
        assertThat(result.getSessionEndedExplicitly()).isTrue();
        assertThat(result.getSessionDurationMs()).isEqualTo(20000L);
        assertThat(result.getSessionEventCount()).isEqualTo(20);
        assertThat(result.getSource()).isEqualTo("redis");
    }

    @Test
    void noSessionAnalysisLifecycleWarning() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-no-sa-lifecycle");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-no-sa");
        redisDetail.setSource("redis");

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1920L);
        anomaly.setEventId("evt-no-sa-lifecycle");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-no-sa");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-no-sa-lifecycle"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-no-sa-lifecycle"))
                .thenReturn(Optional.of(anomaly));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-no-sa"))
                .thenReturn(Optional.empty());

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-no-sa-lifecycle");

        assertThat(result.getSessionEndReason()).isNull();
        assertThat(result.getSessionDurationMs()).isNull();
        assertThat(result.getWarnings()).contains("session_lifecycle_unavailable");
        assertThat(result.getSource()).isEqualTo("redis");
    }

    @Test
    void timestampFallbackRedisPayloadTimestamp() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-ts-payload");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-ts");
        redisDetail.setSource("redis");
        redisDetail.setTimestamp(null);

        V36LiveAlertSummaryDto livePayload = new V36LiveAlertSummaryDto();
        livePayload.setEventId("evt-ts-payload");
        livePayload.setTimestamp(Instant.parse("2026-06-29T16:35:13Z"));

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-ts-payload"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(redisReadService.readValue(CacheKeys.liveAlertPayloadKey("evt-ts-payload"), V36LiveAlertSummaryDto.class))
                .thenReturn(Optional.of(livePayload));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-ts-payload"))
                .thenReturn(Optional.empty());

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-ts-payload");

        assertThat(result.getTimestamp()).isEqualTo(Instant.parse("2026-06-29T16:35:13Z"));
    }

    @Test
    void timestampFallbackRedisCreatedAt() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-ts-created");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-ts");
        redisDetail.setSource("redis");
        redisDetail.setTimestamp(null);

        V36LiveAlertSummaryDto livePayload = new V36LiveAlertSummaryDto();
        livePayload.setEventId("evt-ts-created");
        livePayload.setTimestamp(null);
        livePayload.setCreatedAt(Instant.parse("2026-06-29T17:00:00Z"));

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-ts-created"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(redisReadService.readValue(CacheKeys.liveAlertPayloadKey("evt-ts-created"), V36LiveAlertSummaryDto.class))
                .thenReturn(Optional.of(livePayload));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-ts-created"))
                .thenReturn(Optional.empty());

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-ts-created");

        assertThat(result.getTimestamp()).isEqualTo(Instant.parse("2026-06-29T17:00:00Z"));
    }

    @Test
    void timestampFallbackEventMetadataEventTime() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-ts-meta");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-ts");
        redisDetail.setSource("redis");
        redisDetail.setTimestamp(null);
        redisDetail.setEventMetadata(Map.of("eventTime", "2026-06-29T18:00:00Z"));

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-ts-meta"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(redisReadService.readValue(CacheKeys.liveAlertPayloadKey("evt-ts-meta"), V36LiveAlertSummaryDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-ts-meta"))
                .thenReturn(Optional.empty());

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-ts-meta");

        assertThat(result.getTimestamp()).isEqualTo(Instant.parse("2026-06-29T18:00:00Z"));
    }

    @Test
    void timestampFallbackSqlAnomaly() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-ts-sql");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-ts");
        redisDetail.setSource("redis");
        redisDetail.setTimestamp(null);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1930L);
        anomaly.setEventId("evt-ts-sql");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-ts");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-ts-sql"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(redisReadService.readValue(CacheKeys.liveAlertPayloadKey("evt-ts-sql"), V36LiveAlertSummaryDto.class))
                .thenReturn(Optional.empty());
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-ts-sql"))
                .thenReturn(Optional.of(anomaly));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-ts-sql");

        assertThat(result.getTimestamp()).isEqualTo(Instant.parse("2026-05-25T02:15:00Z"));
    }

    @Test
    void staleNestedSessionLifecycleSyncsToTopLevel() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-lifecycle-sync");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-sync");
        redisDetail.setSource("redis");
        redisDetail.setSessionEndReason(null);
        redisDetail.setSessionEndedExplicitly(false);
        redisDetail.setSessionDurationMs(1000L);
        redisDetail.setSessionEventCount(4);
        redisDetail.setSessionLifecycle(Map.of(
                "sessionEndedExplicitly", false,
                "sessionDurationMs", 1000,
                "sessionEventCount", 4
        ));

        SessionAnalysis session = new SessionAnalysis();
        session.setSessionId("sess-sync");
        session.setSessionDurationSeconds(15L);
        session.setTotalEvents(13);
        session.setInvestigationPayloadJson("{\"sessionEndReason\":\"explicit_logout\",\"sessionEndedExplicitly\":true,\"sessionEndedAt\":\"2026-06-26T16:25:40.106058600Z\",\"sessionDurationMs\":15000,\"sessionEventCount\":13}");

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1940L);
        anomaly.setEventId("evt-lifecycle-sync");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-sync");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));
        anomaly.setFinalRiskScore(63.53);
        anomaly.setAnomalyType("unknown_suspicious_behavior");
        anomaly.setRiskLevel("HIGH");

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-lifecycle-sync"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-lifecycle-sync"))
                .thenReturn(Optional.of(anomaly));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-sync"))
                .thenReturn(Optional.of(session));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-lifecycle-sync");

        assertThat(result.getSessionEndReason()).isEqualTo("explicit_logout");
        assertThat(result.getSessionEndedExplicitly()).isTrue();
        assertThat(result.getSessionDurationMs()).isEqualTo(15000L);
        assertThat(result.getSessionEventCount()).isEqualTo(13);
        assertThat(result.getSessionLifecycle()).isNotNull();
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEndReason", "explicit_logout");
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEndedExplicitly", true);
        assertThat(result.getSessionLifecycle()).containsEntry("sessionDurationMs", 15000L);
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEventCount", 13);
    }

    @Test
    void nestedSessionLifecycleMatchesRedisSource() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-redis-lifecycle-nested");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-redis-nested");
        redisDetail.setSource("redis");
        redisDetail.setSessionDurationMs(1000L);
        redisDetail.setSessionEventCount(4);

        SessionAnalysis session = new SessionAnalysis();
        session.setSessionId("sess-redis-nested");
        session.setSessionDurationSeconds(20L);
        session.setTotalEvents(25);

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1950L);
        anomaly.setEventId("evt-redis-lifecycle-nested");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-redis-nested");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-redis-lifecycle-nested"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-redis-lifecycle-nested"))
                .thenReturn(Optional.of(anomaly));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-redis-nested"))
                .thenReturn(Optional.of(session));

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-redis-lifecycle-nested");

        assertThat(result.getSessionLifecycle()).isNotNull();
        assertThat(result.getSessionLifecycle()).containsEntry("sessionDurationMs", 20000L);
        assertThat(result.getSessionLifecycle()).containsEntry("sessionEventCount", 25);
        assertThat(result.getSessionDurationMs()).isEqualTo(20000L);
        assertThat(result.getSessionEventCount()).isEqualTo(25);
    }

    @Test
    void noLifecycleNestedObjectNull() {
        V36AlertInvestigationDetailDto redisDetail = new V36AlertInvestigationDetailDto();
        redisDetail.setEventId("evt-no-lc-nested");
        redisDetail.setInsuredId("insured-1");
        redisDetail.setSessionId("sess-no-lc");
        redisDetail.setSource("redis");

        AnomalyEvent anomaly = new AnomalyEvent();
        anomaly.setId(1960L);
        anomaly.setEventId("evt-no-lc-nested");
        anomaly.setInsuredId("insured-1");
        anomaly.setSessionId("sess-no-lc");
        anomaly.setEventTime(Instant.parse("2026-05-25T02:15:00Z"));

        when(redisReadService.readValue(CacheKeys.alertInvestigationKey("evt-no-lc-nested"), V36AlertInvestigationDetailDto.class))
                .thenReturn(Optional.of(redisDetail));
        when(anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc("evt-no-lc-nested"))
                .thenReturn(Optional.of(anomaly));
        when(sessionAnalysisRepository.findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc("insured-1", "sess-no-lc"))
                .thenReturn(Optional.empty());

        V36AlertInvestigationDetailDto result = service.getAlertDetail("evt-no-lc-nested");

        assertThat(result.getSessionLifecycle()).isNull();
        assertThat(result.getWarnings()).contains("session_lifecycle_unavailable");
    }

    private static AnomalyEvent anomaly(String eventId, String riskLevel, Instant eventTime) {
        AnomalyEvent a = new AnomalyEvent();
        a.setId(1L);
        a.setEventId(eventId);
        a.setRiskLevel(riskLevel);
        a.setEventTime(eventTime);
        a.setInsuredId("insured-1");
        return a;
    }
}
