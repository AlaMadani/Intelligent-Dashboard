package com.neo.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36AlertInvestigationDetailDto;
import com.neo.dashboard.dto.v36.V36AnomalyTypeAttributionDto;
import com.neo.dashboard.dto.v36.V36ChurnContextDto;
import com.neo.dashboard.dto.v36.V36ForecastContextDto;
import com.neo.dashboard.dto.v36.V36LiveAlertSummaryDto;
import com.neo.dashboard.dto.v36.V36ModelContributionsDto;
import com.neo.dashboard.dto.v36.V36ModelScoresDto;
import com.neo.dashboard.dto.v36.V36PersonaDisabledDto;
import com.neo.dashboard.dto.v36.V36RuleEvidenceDto;
import com.neo.dashboard.dto.v36.V36SequenceEvidenceDto;
import com.neo.dashboard.dto.v36.V36TabularEvidenceDto;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.entity.SessionAnalysis;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class V36AlertService {

    private final V36RedisReadService redisReadService;
    private final AnomalyEventRepository anomalyEventRepository;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final ObjectMapper objectMapper;

    public ApiPageResponse<V36LiveAlertSummaryDto> getLiveAlerts(String riskLevel,
                                                                 String anomalyType,
                                                                 String insuredId,
                                                                 String sessionId,
                                                                 Instant from,
                                                                 Instant to,
                                                                 int limit,
                                                                 int offset) {
        List<V36LiveAlertSummaryDto> redisAlerts = readAlertList(CacheKeys.ALERTS_LIVE_V36, limit + offset);
        if (!redisAlerts.isEmpty()) {
            List<V36LiveAlertSummaryDto> filtered = filterAlerts(redisAlerts, riskLevel, anomalyType, insuredId, sessionId, from, to);
            return page(filtered, limit, offset);
        }
        return getSqlAlerts(riskLevel, anomalyType, insuredId, sessionId, from, to, limit, offset);
    }

    public ApiPageResponse<V36LiveAlertSummaryDto> getCriticalAlerts(int limit, int offset) {
        List<V36LiveAlertSummaryDto> redisAlerts = readAlertList(CacheKeys.ALERTS_CRITICAL_V36, limit + offset);
        if (!redisAlerts.isEmpty()) {
            return page(redisAlerts, limit, offset);
        }
        return getSqlAlerts("CRITICAL", null, null, null, null, null, limit, offset);
    }

    public ApiPageResponse<V36LiveAlertSummaryDto> getUserAlerts(String insuredId,
                                                                 String riskLevel,
                                                                 Instant from,
                                                                 Instant to,
                                                                 int limit,
                                                                 int offset) {
        List<V36LiveAlertSummaryDto> redisAlerts = readAlertList(CacheKeys.userAlertsKey(insuredId), limit + offset);
        if (!redisAlerts.isEmpty()) {
            List<V36LiveAlertSummaryDto> filtered = filterAlerts(redisAlerts, riskLevel, null, insuredId, null, from, to);
            return page(filtered, limit, offset);
        }
        return getSqlAlerts(riskLevel, null, insuredId, null, from, to, limit, offset);
    }

    @Transactional(readOnly = true)
    public V36AlertInvestigationDetailDto getAlertDetail(String eventId) {
        Optional<V36AlertInvestigationDetailDto> redisDetail = redisReadService
                .readValue(CacheKeys.alertInvestigationKey(eventId), V36AlertInvestigationDetailDto.class)
                .map(this::normalizeInvestigation);
        if (redisDetail.isPresent()) {
            return redisDetail.get();
        }

        AnomalyEvent anomaly = anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Alert not found"));

        Optional<V36AlertInvestigationDetailDto> eventPayload = parseInvestigationPayload(anomaly.getInvestigationPayloadJson());
        if (eventPayload.isPresent()) {
            V36AlertInvestigationDetailDto detail = eventPayload.get();
            if (!hasText(detail.getSource())) {
                detail.setSource("sql-payload");
            }
            if (detail.getId() == null) {
                detail.setId(anomaly.getId());
            }
            if (detail.getAnomalyDbId() == null) {
                detail.setAnomalyDbId(anomaly.getId());
            }
            return normalizeInvestigation(detail);
        }

        SessionAnalysis session = sessionAnalysisRepository
                .findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anomaly.getInsuredId(), anomaly.getSessionId())
                .orElse(null);
        if (session != null) {
            Optional<V36AlertInvestigationDetailDto> sessionPayload = parseInvestigationPayload(session.getInvestigationPayloadJson());
            if (sessionPayload.isPresent()) {
                V36AlertInvestigationDetailDto detail = sessionPayload.get();
                if (!hasText(detail.getSource())) {
                    detail.setSource("sql-payload");
                }
                if (detail.getId() == null) {
                    detail.setId(anomaly.getId());
                }
                if (detail.getAnomalyDbId() == null) {
                    detail.setAnomalyDbId(anomaly.getId());
                }
                return normalizeInvestigation(detail);
            }
        }

        return buildInvestigationFromSql(anomaly, session);
    }

    private List<V36LiveAlertSummaryDto> readAlertList(String key, int limit) {
        return redisReadService.readItems(key, V36LiveAlertSummaryDto.class, normalizeLimit(limit)).stream()
                .map(alert -> {
                    if (!hasText(alert.getSource())) {
                        alert.setSource("redis");
                    }
                    return normalizeAlert(alert);
                })
                .toList();
    }

    private ApiPageResponse<V36LiveAlertSummaryDto> getSqlAlerts(String riskLevel,
                                                                 String anomalyType,
                                                                 String insuredId,
                                                                 String sessionId,
                                                                 Instant from,
                                                                 Instant to,
                                                                 int limit,
                                                                 int offset) {
        PageRequest pageable = PageRequest.of(
                Math.max(offset, 0) / normalizeLimit(limit),
                normalizeLimit(limit),
                Sort.by(Sort.Direction.DESC, "eventTime")
        );
        Page<AnomalyEvent> page = anomalyEventRepository.searchV36Alerts(
                blankToNull(riskLevel),
                blankToNull(anomalyType),
                blankToNull(insuredId),
                blankToNull(sessionId),
                from,
                to,
                pageable
        );
        List<V36LiveAlertSummaryDto> items = page.getContent().stream()
                .map(this::toLiveAlert)
                .toList();
        return ApiPageResponse.of(items, normalizeLimit(limit), offset, page.hasNext());
    }

    private ApiPageResponse<V36LiveAlertSummaryDto> page(List<V36LiveAlertSummaryDto> source, int limit, int offset) {
        int normalizedLimit = normalizeLimit(limit);
        int normalizedOffset = Math.max(offset, 0);
        if (source == null || source.isEmpty() || normalizedOffset >= source.size()) {
            return ApiPageResponse.of(List.of(), normalizedLimit, normalizedOffset, false);
        }
        int end = Math.min(normalizedOffset + normalizedLimit, source.size());
        return ApiPageResponse.of(source.subList(normalizedOffset, end), normalizedLimit, normalizedOffset, end < source.size());
    }

    private List<V36LiveAlertSummaryDto> filterAlerts(List<V36LiveAlertSummaryDto> source,
                                                      String riskLevel,
                                                      String anomalyType,
                                                      String insuredId,
                                                      String sessionId,
                                                      Instant from,
                                                      Instant to) {
        return source.stream()
                .filter(alert -> matches(riskLevel, alert.getRiskLevel()))
                .filter(alert -> matches(anomalyType, alert.getAnomalyType()))
                .filter(alert -> matches(insuredId, alert.getInsuredId()))
                .filter(alert -> matches(sessionId, alert.getSessionId()))
                .filter(alert -> from == null || alert.getTimestamp() == null || !alert.getTimestamp().isBefore(from))
                .filter(alert -> to == null || alert.getTimestamp() == null || !alert.getTimestamp().isAfter(to))
                .toList();
    }

    private V36LiveAlertSummaryDto toLiveAlert(AnomalyEvent anomaly) {
        V36LiveAlertSummaryDto dto = new V36LiveAlertSummaryDto();
        dto.setId(anomaly.getId());
        dto.setAnomalyDbId(anomaly.getId());
        dto.setEventId(anomaly.getEventId());
        dto.setRecordId(anomaly.getEventId());
        dto.setInsuredId(anomaly.getInsuredId());
        dto.setSessionId(anomaly.getSessionId());
        dto.setTimestamp(firstNonNull(anomaly.getEventTime(), anomaly.getDetectedAt()));
        dto.setRiskLevel(firstText(anomaly.getRiskLevel(), anomaly.getAnomalyTier()));
        dto.setFinalRiskScore(firstNonNull(anomaly.getFinalRiskScore(), anomaly.getRiskScore(), anomaly.getAnomalyScore()));
        dto.setAnomalyType(anomaly.getAnomalyType());
        dto.setAnomalyTypeConfidence(firstNonNull(anomaly.getAnomalyTypeConfidence(), anomaly.getTypeConfidence()));
        dto.setXgboostAnomalyScore(anomaly.getXgboostAnomalyScore());
        dto.setXgboostAnomalyScore100(anomaly.getXgboostAnomalyScore100());
        dto.setLightgbmAlertScore(anomaly.getLightgbmAlertScore());
        dto.setLightgbmAlertScore100(anomaly.getLightgbmAlertScore100());
        dto.setTransformerRiskScore100(anomaly.getTransformerRiskScore100());
        dto.setTcnRiskScore100(anomaly.getTcnRiskScore100());
        dto.setRuleRiskScore(anomaly.getRuleRiskScore());
        dto.setModelContributions(buildModelContributions(anomaly.getModelContributionsJson(), null));
        dto.setTriggeredRuleCodes(parseStringList(firstText(anomaly.getTriggeredRulesJson(), anomaly.getRuleType())));
        dto.setChurnProbability(anomaly.getChurnProbability());
        dto.setChurnRiskLevel(anomaly.getChurnRiskLevel());
        dto.setPersonaLabel(firstText(anomaly.getPersonaLabel(), "persona_disabled"));
        dto.setLlmEvidencePayloadAvailable(hasText(anomaly.getLlmExplanationEvidencePayloadJson()));
        dto.setLlmEvidenceRedisKey(CacheKeys.alertLlmEvidenceKey(anomaly.getEventId()));
        dto.setAlertStatus("OPEN");
        dto.setCreatedAt(anomaly.getDetectedAt());
        dto.setSource("sql");
        populateEventMetadataFields(dto, anomaly.getEventJson());
        return normalizeAlert(dto);
    }

    private void populateEventMetadataFields(V36LiveAlertSummaryDto dto, String eventJson) {
        JsonNode node = parseJson(eventJson);
        if (node == null) {
            return;
        }
        dto.setEventAction(firstText(dto.getEventAction(), textAt(node, "eventAction"), textAt(node, "action")));
        dto.setApiTemplate(firstText(dto.getApiTemplate(), textAt(node, "apiTemplate"), textAt(node, "route")));
        dto.setApiFamily(firstText(dto.getApiFamily(), textAt(node, "apiFamily")));
        dto.setController(firstText(dto.getController(), textAt(node, "controller")));
        dto.setPage(firstText(dto.getPage(), textAt(node, "page")));
        dto.setCountry(firstText(dto.getCountry(), textAt(node, "country"), textAt(node, "countryCode")));
        dto.setDevice(firstText(dto.getDevice(), textAt(node, "device")));
        dto.setBrowser(firstText(dto.getBrowser(), textAt(node, "browser")));
        dto.setOs(firstText(dto.getOs(), textAt(node, "os")));
        dto.setHttpMethod(firstText(dto.getHttpMethod(), textAt(node, "httpMethod"), textAt(node, "method")));
        dto.setStatus(firstText(dto.getStatus(), textAt(node, "status")));
    }

    private V36AlertInvestigationDetailDto buildInvestigationFromSql(AnomalyEvent anomaly, SessionAnalysis session) {
        V36AlertInvestigationDetailDto detail = new V36AlertInvestigationDetailDto();
        detail.setId(anomaly.getId());
        detail.setAnomalyDbId(anomaly.getId());
        detail.setEventId(anomaly.getEventId());
        detail.setRecordId(anomaly.getEventId());
        detail.setInsuredId(anomaly.getInsuredId());
        detail.setSessionId(anomaly.getSessionId());
        detail.setTimestamp(firstNonNull(anomaly.getEventTime(), anomaly.getDetectedAt()));
        detail.setRiskLevel(firstText(anomaly.getRiskLevel(), anomaly.getAnomalyTier()));
        detail.setFinalRiskScore(firstNonNull(anomaly.getFinalRiskScore(), anomaly.getRiskScore(), session == null ? null : session.getFinalRiskScore()));
        detail.setAnomalyType(anomaly.getAnomalyType());
        detail.setAnomalyTypeConfidence(firstNonNull(anomaly.getAnomalyTypeConfidence(), anomaly.getTypeConfidence()));
        detail.setTriggeredRules(parseStringList(firstText(anomaly.getTriggeredRulesJson(), anomaly.getRuleType(), session == null ? null : session.getTriggeredRulesJson())));
        detail.setEventMetadata(parseObjectMap(anomaly.getEventJson()));
        detail.setModelScores(buildModelScores(anomaly, session));
        detail.setModelContributions(buildModelContributions(anomaly.getModelContributionsJson(), session == null ? null : session.getModelContributionsJson()));
        detail.setSequenceEvidence(buildSequenceEvidence(session));
        detail.setTabularEvidence(buildTabularEvidence(session));
        detail.setRuleEvidence(buildRuleEvidence(anomaly, session));
        detail.setAnomalyTypeAttribution(buildAttribution(anomaly, session));
        detail.setChurnContext(buildChurnContext(anomaly, session));
        detail.setForecastContext(buildForecastContext(session));
        detail.setPersona(buildPersona(session, anomaly));
        detail.setRuntimeWarnings(parseStringList(firstText(anomaly.getRuntimeWarningsJson(), session == null ? null : session.getWarningsJson())));
        detail.setLlmEvidencePayloadAvailable(hasText(anomaly.getLlmExplanationEvidencePayloadJson())
                || (session != null && hasText(session.getLlmExplanationEvidencePayloadJson())));
        detail.setLlmEvidenceRedisKey(CacheKeys.alertLlmEvidenceKey(anomaly.getEventId()));
        detail.setSource("sql");
        detail.setRawPayload(parseJson(firstText(anomaly.getInvestigationPayloadJson(), session == null ? null : session.getInvestigationPayloadJson())));
        return normalizeInvestigation(detail);
    }

    private V36ModelScoresDto buildModelScores(AnomalyEvent anomaly, SessionAnalysis session) {
        V36ModelScoresDto scores = new V36ModelScoresDto();
        scores.setXgboostAnomalyScore(firstNonNull(anomaly.getXgboostAnomalyScore(), session == null ? null : session.getXgboostAnomalyScore()));
        scores.setXgboostAnomalyScore100(firstNonNull(anomaly.getXgboostAnomalyScore100(), session == null ? null : session.getXgboostAnomalyScore100()));
        scores.setLightgbmAlertScore(firstNonNull(anomaly.getLightgbmAlertScore(), session == null ? null : session.getLightgbmAlertScore()));
        scores.setLightgbmAlertScore100(firstNonNull(anomaly.getLightgbmAlertScore100(), session == null ? null : session.getLightgbmAlertScore100()));
        scores.setCatboostAnomalyScore(session == null ? null : session.getCatboostAnomalyScore());
        scores.setOneclasssvmNoveltyScore(session == null ? null : session.getOneclasssvmNoveltyScore());
        scores.setTransformerSurpriseScore(session == null ? null : session.getTransformerSurpriseScore());
        scores.setTransformerRiskScore100(firstNonNull(anomaly.getTransformerRiskScore100(), session == null ? null : session.getTransformerRiskScore100()));
        scores.setTcnSurpriseScore(session == null ? null : session.getTcnSurpriseScore());
        scores.setTcnRiskScore100(firstNonNull(anomaly.getTcnRiskScore100(), session == null ? null : session.getTcnRiskScore100()));
        scores.setRuleRiskScore(firstNonNull(anomaly.getRuleRiskScore(), session == null ? null : session.getRuleRiskScore()));
        scores.setBusinessContextScore(session == null ? null : session.getBusinessContextScore());
        scores.setAggregationBoost(session == null ? null : session.getAggregationBoost());
        scores.setFinalRiskScore(firstNonNull(anomaly.getFinalRiskScore(), session == null ? null : session.getFinalRiskScore()));
        return scores;
    }

    private V36ModelContributionsDto buildModelContributions(String eventJson, String sessionJson) {
        Map<String, Object> raw = parseObjectMap(firstText(eventJson, sessionJson));
        V36ModelContributionsDto dto = new V36ModelContributionsDto();
        dto.setXgboost(number(raw, "xgboost"));
        dto.setLightgbm(number(raw, "lightgbm"));
        dto.setTransformer(number(raw, "transformer"));
        dto.setTcn(number(raw, "tcn"));
        dto.setRules(number(raw, "rules"));
        dto.setBusinessContext(number(raw, "businessContext", "business_context"));
        dto.setAggregationBoost(number(raw, "aggregationBoost", "aggregation_boost"));
        dto.setRaw(raw);
        return dto;
    }

    private V36SequenceEvidenceDto buildSequenceEvidence(SessionAnalysis session) {
        if (session == null) {
            return null;
        }
        V36SequenceEvidenceDto dto = new V36SequenceEvidenceDto();
        dto.setSelectedSequenceModel(firstText(session.getSelectedSequenceModel(), session.getSequenceModelArtifact()));
        dto.setSequenceModelArtifact(firstText(session.getTransformerArtifact(), session.getTcnArtifact(), session.getSequenceModelArtifact()));
        dto.setSequenceCatScore(session.getSequenceCatScore());
        dto.setSequenceContScore(session.getSequenceContScore());
        dto.setSequenceCtxScore(session.getSequenceCtxScore());
        dto.setTopSequenceSurpriseFields(parseListOfMaps(session.getTopSequenceSurpriseFieldsJson()));
        return dto;
    }

    private V36TabularEvidenceDto buildTabularEvidence(SessionAnalysis session) {
        if (session == null) {
            return null;
        }
        V36TabularEvidenceDto dto = new V36TabularEvidenceDto();
        dto.setFeatureWarnings(parseObjectMap(session.getWarningsJson()));
        dto.setRaw(parseObjectMap(session.getModelArtifactsJson()));
        return dto;
    }

    private V36RuleEvidenceDto buildRuleEvidence(AnomalyEvent anomaly, SessionAnalysis session) {
        V36RuleEvidenceDto dto = new V36RuleEvidenceDto();
        dto.setRuleRiskScore(firstNonNull(anomaly.getRuleRiskScore(), session == null ? null : session.getRuleRiskScore()));
        dto.setTriggeredRules(parseStringList(firstText(anomaly.getTriggeredRulesJson(), anomaly.getRuleType(), session == null ? null : session.getTriggeredRulesJson())));
        dto.setRuleContributions(parseObjectMap(session == null ? null : session.getRuleContributionsJson()));
        return dto;
    }

    private V36AnomalyTypeAttributionDto buildAttribution(AnomalyEvent anomaly, SessionAnalysis session) {
        V36AnomalyTypeAttributionDto dto = new V36AnomalyTypeAttributionDto();
        dto.setAnomalyType(anomaly.getAnomalyType());
        dto.setConfidence(firstNonNull(anomaly.getAnomalyTypeConfidence(), anomaly.getTypeConfidence(), session == null ? null : session.getAnomalyTypeConfidence()));
        dto.setSource(firstText(anomaly.getAnomalyTypeSource(), session == null ? null : session.getAnomalyTypeSource()));
        dto.setEvidence(parseObjectMap(firstText(anomaly.getAnomalyTypeEvidenceJson(), session == null ? null : session.getAnomalyTypeEvidenceJson())));
        return dto;
    }

    private V36ChurnContextDto buildChurnContext(AnomalyEvent anomaly, SessionAnalysis session) {
        V36ChurnContextDto dto = new V36ChurnContextDto();
        dto.setProbability(firstNonNull(anomaly.getChurnProbability(), session == null ? null : session.getChurnProbability()));
        dto.setRiskLevel(firstText(anomaly.getChurnRiskLevel(), session == null ? null : session.getChurnRiskLevel()));
        dto.setModelName(session == null ? null : session.getChurnModelName());
        dto.setModelArtifact(session == null ? null : session.getChurnModelArtifact());
        dto.setFeatureWarnings(parseObjectMap(session == null ? null : session.getChurnFeatureWarningsJson()));
        return dto;
    }

    private V36ForecastContextDto buildForecastContext(SessionAnalysis session) {
        if (session == null) {
            return null;
        }
        V36ForecastContextDto dto = new V36ForecastContextDto();
        dto.setRaw(parseObjectMap(session.getForecastContextJson()));
        return dto;
    }

    private V36PersonaDisabledDto buildPersona(SessionAnalysis session, AnomalyEvent anomaly) {
        V36PersonaDisabledDto dto = new V36PersonaDisabledDto();
        dto.setEnabled(false);
        dto.setCluster(firstNonNull(session == null ? null : session.getPersonaCluster(), anomaly.getPersonaCluster(), -1));
        dto.setLabel(firstText(session == null ? null : session.getPersonaLabel(), anomaly.getPersonaLabel(), "persona_disabled"));
        dto.setSource(firstText(session == null ? null : session.getPersonaSource(), "disabled_v3_6_refactor"));
        dto.setConfidence(session == null ? null : session.getPersonaConfidence());
        return dto;
    }

    private Optional<V36AlertInvestigationDetailDto> parseInvestigationPayload(String payload) {
        if (!hasText(payload)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, V36AlertInvestigationDetailDto.class));
        } catch (Exception e) {
            log.warn("Malformed investigation payload JSON", e);
            return Optional.empty();
        }
    }

    private V36AlertInvestigationDetailDto normalizeInvestigation(V36AlertInvestigationDetailDto detail) {
        detail.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        if (!hasText(detail.getLlmEvidenceRedisKey()) && hasText(detail.getEventId())) {
            detail.setLlmEvidenceRedisKey(CacheKeys.alertLlmEvidenceKey(detail.getEventId()));
        }
        if (detail.getRuntimeWarnings() == null) {
            detail.setRuntimeWarnings(List.of());
        }
        if (detail.getTriggeredRules() == null) {
            detail.setTriggeredRules(List.of());
        }
        if (detail.getWarnings() == null) {
            detail.setWarnings(List.of());
        }
        if (detail.getLlm() == null && hasText(detail.getEventId())) {
            detail.setLlm(buildLlmSection(detail.getEventId(), detail.getLlmEvidencePayloadAvailable(), detail.getLlmEvidenceRedisKey()));
        }
        if (!hasText(detail.getSource())) {
            detail.setSource("redis");
        }
        return detail;
    }

    private V36LiveAlertSummaryDto normalizeAlert(V36LiveAlertSummaryDto alert) {
        alert.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        if (!hasText(alert.getRecordId())) {
            alert.setRecordId(alert.getEventId());
        }
        if (!hasText(alert.getLlmEvidenceRedisKey()) && hasText(alert.getEventId())) {
            alert.setLlmEvidenceRedisKey(CacheKeys.alertLlmEvidenceKey(alert.getEventId()));
        }
        if (alert.getTriggeredRuleCodes() == null) {
            alert.setTriggeredRuleCodes(List.of());
        }
        if (!hasText(alert.getPersonaLabel())) {
            alert.setPersonaLabel("persona_disabled");
        }
        if (alert.getWarnings() == null) {
            alert.setWarnings(List.of());
        }
        return alert;
    }

    private List<String> parseStringList(String jsonOrValue) {
        if (!hasText(jsonOrValue)) {
            return List.of();
        }
        String trimmed = jsonOrValue.trim();
        try {
            if (trimmed.startsWith("[")) {
                return objectMapper.readValue(trimmed, new TypeReference<List<String>>() { });
            }
        } catch (Exception ignored) {
            return List.of();
        }
        if (trimmed.startsWith("{")) {
            Map<String, Object> map = parseObjectMap(trimmed);
            return map.keySet().stream().toList();
        }
        List<String> values = new ArrayList<>();
        for (String value : trimmed.split(",")) {
            String item = value.trim();
            if (!item.isEmpty()) {
                values.add(item);
            }
        }
        return values;
    }

    private Map<String, Object> parseObjectMap(String json) {
        if (!hasText(json)) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> parseListOfMaps(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private JsonNode parseJson(String json) {
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String textAt(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) {
            return null;
        }
        String value = node.path(fieldName).asText(null);
        return hasText(value) ? value : null;
    }

    private Double number(Map<String, Object> map, String... names) {
        if (map == null || names == null) {
            return null;
        }
        for (String name : names) {
            Object value = map.get(name);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        }
        return null;
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean matches(String expected, String actual) {
        return !hasText(expected) || (hasText(actual) && expected.equalsIgnoreCase(actual));
    }

    private Map<String, Object> buildLlmSection(String eventId, Boolean evidenceAvailable, String evidenceRedisKey) {
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("evidenceAvailable", Boolean.TRUE.equals(evidenceAvailable));
        llm.put("evidenceRedisKey", evidenceRedisKey);
        llm.put("evidenceEndpoint", "/api/v1/explanations/alerts/" + eventId + "/evidence");
        llm.put("cachedExplanationEndpoint", "/api/v1/explanations/alerts/" + eventId);
        llm.put("generateExplanationEndpoint", "POST /api/v1/explanations/alerts/" + eventId);
        return llm;
    }

    private String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 100;
        }
        return Math.min(limit, 500);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
