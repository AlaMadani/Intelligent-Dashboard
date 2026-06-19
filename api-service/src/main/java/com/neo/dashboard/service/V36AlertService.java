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
import com.neo.dashboard.dto.v36.V36LlmEvidencePayloadDto;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class V36AlertService {

    private final V36RedisReadService redisReadService;
    private final AnomalyEventRepository anomalyEventRepository;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final ObjectMapper objectMapper;
    private final DashboardSnapshotFallbackService snapshotFallbackService;
    private final LlmEvidenceReadService evidenceReadService;

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

        List<V36LiveAlertSummaryDto> snapshotAlerts = snapshotFallbackService
                .readListFromSql(V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", normalizeLimit(limit) + Math.max(offset, 0));
        if (!snapshotAlerts.isEmpty()) {
            List<V36LiveAlertSummaryDto> processed = snapshotAlerts.stream()
                    .peek(a -> { if (!hasText(a.getSource())) a.setSource("sql_fallback"); })
                    .map(this::normalizeAlert)
                    .map(this::hydrateAlertFields)
                    .toList();
            List<V36LiveAlertSummaryDto> deduped = deduplicateByEventIdWithRichness(processed);
            List<V36LiveAlertSummaryDto> hydrated = hydrateLiveAlertsFromStoredPayloads(deduped);
            List<V36LiveAlertSummaryDto> filtered = filterAlerts(hydrated, riskLevel, anomalyType, insuredId, sessionId, from, to);
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
                .map(this::normalizeInvestigation)
                .map(detail -> hydrateEvidenceFields(detail, eventId));
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
            return hydrateEvidenceFields(hydrateDetailTimestamp(normalizeInvestigation(detail), anomaly), eventId);
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
                return hydrateEvidenceFields(hydrateDetailTimestamp(normalizeInvestigation(detail), anomaly), eventId);
            }
        }

        return hydrateEvidenceFields(buildInvestigationFromSql(anomaly, session), eventId);
    }

    private V36AlertInvestigationDetailDto hydrateEvidenceFields(V36AlertInvestigationDetailDto detail, String eventId) {
        if (detail == null) {
            return null;
        }
        boolean needsHydration = detail.getSequenceEvidence() == null
                || detail.getTabularEvidence() == null
                || detail.getRuleEvidence() == null
                || detail.getChurnContext() == null
                || detail.getForecastContext() == null;
        if (!needsHydration) {
            return detail;
        }
        try {
            evidenceReadService.readEvidence(eventId).ifPresent(evidence -> {
                try {
                    V36LlmEvidencePayloadDto evidencePayload = objectMapper.treeToValue(evidence, V36LlmEvidencePayloadDto.class);
                    if (detail.getSequenceEvidence() == null && evidencePayload.getSequenceEvidence() != null) {
                        detail.setSequenceEvidence(evidencePayload.getSequenceEvidence());
                    }
                    if (detail.getTabularEvidence() == null && evidencePayload.getTabularEvidence() != null) {
                        detail.setTabularEvidence(evidencePayload.getTabularEvidence());
                    }
                    if (detail.getRuleEvidence() == null && evidencePayload.getRuleEvidence() != null) {
                        detail.setRuleEvidence(evidencePayload.getRuleEvidence());
                    }
                    if (detail.getChurnContext() == null && evidencePayload.getChurnContext() != null) {
                        detail.setChurnContext(evidencePayload.getChurnContext());
                    }
                    if (detail.getForecastContext() == null && evidencePayload.getForecastContext() != null) {
                        detail.setForecastContext(evidencePayload.getForecastContext());
                    }
                    if (detail.getAnomalyTypeAttribution() == null && evidencePayload.getAnomalyTypeAttribution() != null) {
                        detail.setAnomalyTypeAttribution(evidencePayload.getAnomalyTypeAttribution());
                    }
                    if (detail.getModelScores() == null && evidencePayload.getModelScores() != null) {
                        detail.setModelScores(evidencePayload.getModelScores());
                    }
                    if (detail.getModelContributions() == null && evidencePayload.getModelContributions() != null) {
                        detail.setModelContributions(evidencePayload.getModelContributions());
                    }
                    if (detail.getEventMetadata() == null && evidencePayload.getEventMetadata() != null) {
                        detail.setEventMetadata(evidencePayload.getEventMetadata());
                    }
                } catch (Exception e) {
                    log.debug("Failed to hydrate evidence fields from payload for eventId={}", eventId, e);
                }
            });
        } catch (Exception e) {
            log.debug("Failed to read evidence for eventId={}", eventId, e);
        }
        return detail;
    }

    private V36AlertInvestigationDetailDto hydrateDetailTimestamp(V36AlertInvestigationDetailDto detail, AnomalyEvent anomaly) {
        if (detail != null && detail.getTimestamp() == null && anomaly != null) {
            detail.setTimestamp(firstNonNull(anomaly.getEventTime(), anomaly.getDetectedAt()));
        }
        return detail;
    }

    private List<V36LiveAlertSummaryDto> readAlertList(String key, int limit) {
        List<V36LiveAlertSummaryDto> alerts = redisReadService.readItems(key, V36LiveAlertSummaryDto.class, normalizeLimit(limit)).stream()
                .map(alert -> {
                    if (!hasText(alert.getSource())) {
                        alert.setSource("redis");
                    }
                    return normalizeAlert(alert);
                })
                .toList();
        List<V36LiveAlertSummaryDto> deduped = deduplicateByEventIdWithRichness(alerts);
        List<V36LiveAlertSummaryDto> hydrated = deduped.stream().map(this::hydrateAlertFields).toList();
        return hydrateLiveAlertsFromStoredPayloads(hydrated);
    }

    private List<V36LiveAlertSummaryDto> deduplicateByEventIdWithRichness(List<V36LiveAlertSummaryDto> alerts) {
        if (alerts == null || alerts.size() <= 1) {
            return alerts;
        }
        Map<String, V36LiveAlertSummaryDto> best = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (V36LiveAlertSummaryDto alert : alerts) {
            String eventId = alert.getEventId();
            if (eventId == null) {
                continue;
            }
            V36LiveAlertSummaryDto existing = best.get(eventId);
            if (existing == null) {
                best.put(eventId, alert);
            } else {
                duplicateCount++;
                if (computeRichnessScore(alert) > computeRichnessScore(existing)) {
                    best.put(eventId, alert);
                }
            }
        }
        if (duplicateCount > 0) {
            log.warn("Duplicate alert rows deduplicated by eventId (kept richest): {}", duplicateCount);
        }
        return List.copyOf(best.values());
    }

    private int computeRichnessScore(V36LiveAlertSummaryDto a) {
        int score = 0;
        if (hasText(a.getRiskLevel())) score += 10;
        if (a.getTimestamp() != null) score += 10;
        if (hasText(a.getEventAction())) score += 8;
        if (a.getFinalRiskScore() != null) score += 5;
        if (hasText(a.getAnomalyType())) score += 5;
        if (hasText(a.getApiTemplate())) score += 5;
        if (hasText(a.getApiFamily())) score += 5;
        if (hasText(a.getController())) score += 5;
        if (hasText(a.getPage())) score += 5;
        if (hasText(a.getCountry())) score += 5;
        if (hasText(a.getDevice())) score += 5;
        if (hasText(a.getBrowser())) score += 5;
        if (hasText(a.getOs())) score += 5;
        if (hasText(a.getHttpMethod())) score += 5;
        if (hasText(a.getStatus())) score += 5;
        if (a.getXgboostAnomalyScore100() != null) score += 8;
        if (a.getLightgbmAlertScore100() != null) score += 8;
        if (a.getTransformerRiskScore100() != null) score += 8;
        if (a.getTcnRiskScore100() != null) score += 8;
        if (a.getModelContributions() != null) score += 8;
        if (a.getTriggeredRuleCodes() != null && !a.getTriggeredRuleCodes().isEmpty()) score += 8;
        if (a.getChurnProbability() != null) score += 5;
        if (hasText(a.getChurnRiskLevel())) score += 5;
        if (hasText(a.getPersonaLabel()) && !"persona_disabled".equals(a.getPersonaLabel())) score += 3;
        if (Boolean.TRUE.equals(a.getLlmEvidencePayloadAvailable())) score += 3;
        if (hasText(a.getAlertStatus())) score += 2;
        return score;
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
        enrichSessionLifecycleFromPayload(detail, anomaly.getInvestigationPayloadJson());
        enrichSessionLifecycleFromPayload(detail, session == null ? null : session.getInvestigationPayloadJson());
        if (session != null && session.getSessionDurationSeconds() != null) {
            detail.setSessionDurationMs(session.getSessionDurationSeconds() * 1000L);
        }
        if (session != null && session.getTotalEvents() != null) {
            detail.setSessionEventCount(session.getTotalEvents());
        }
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

    private void populateSessionLifecycle(V36AlertInvestigationDetailDto detail) {
        detail.buildSessionLifecycle();
    }

    private void enrichSessionLifecycleFromPayload(V36AlertInvestigationDetailDto detail, String payloadJson) {
        if (!hasText(payloadJson)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            if (node == null || !node.isObject()) {
                return;
            }
            if (detail.getSessionEndReason() == null) {
                detail.setSessionEndReason(textAt(node, "sessionEndReason"));
            }
            if (detail.getSessionEndedExplicitly() == null && node.has("sessionEndedExplicitly")) {
                detail.setSessionEndedExplicitly(node.path("sessionEndedExplicitly").asBoolean(false));
            }
            if (detail.getSessionEndedAt() == null && node.has("sessionEndedAt")) {
                String endedAt = node.path("sessionEndedAt").asText(null);
                if (endedAt != null) {
                    try {
                        detail.setSessionEndedAt(Instant.parse(endedAt));
                    } catch (Exception ignored) {}
                }
            }
            if (detail.getSessionDurationMs() == null && node.has("sessionDurationMs")) {
                detail.setSessionDurationMs(node.path("sessionDurationMs").asLong(0));
            }
            if (detail.getSessionEventCount() == null && node.has("sessionEventCount")) {
                detail.setSessionEventCount(node.path("sessionEventCount").asInt(0));
            }
            JsonNode lifecycle = node.path("sessionLifecycle");
            if (lifecycle.isObject() && detail.getSessionLifecycle() == null) {
                try {
                    Map<String, Object> lcMap = objectMapper.convertValue(lifecycle, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    if (!lcMap.isEmpty()) {
                        detail.setSessionLifecycle(lcMap);
                    }
                } catch (Exception ignored) {}
            }
            if (detail.getRuntimeWarnings() == null || detail.getRuntimeWarnings().isEmpty()) {
                JsonNode warnings = node.path("runtimeWarnings");
                if (warnings.isArray()) {
                    List<String> runtimeWarnings = objectMapper.convertValue(warnings, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    detail.setRuntimeWarnings(runtimeWarnings);
                }
            }
            JsonNode modelScoresNode = node.path("modelScores");
            if (modelScoresNode.isObject() && detail.getModelScores() == null) {
                try {
                    detail.setModelScores(objectMapper.treeToValue(modelScoresNode, com.neo.dashboard.dto.v36.V36ModelScoresDto.class));
                } catch (Exception ignored) {}
            }
            JsonNode modelContributionsNode = node.path("modelContributions");
            if (modelContributionsNode.isObject() && detail.getModelContributions() == null) {
                try {
                    detail.setModelContributions(objectMapper.treeToValue(modelContributionsNode, com.neo.dashboard.dto.v36.V36ModelContributionsDto.class));
                } catch (Exception ignored) {}
            }
            if (detail.getTriggeredRules() == null || detail.getTriggeredRules().isEmpty()) {
                JsonNode rules = node.path("triggeredRules");
                if (rules.isArray()) {
                    List<String> triggeredRules = objectMapper.convertValue(rules, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    detail.setTriggeredRules(triggeredRules);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to enrich session lifecycle from payload", e);
        }
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
        extractSessionLifecycleFromNested(detail);
        populateSessionLifecycle(detail);
        return detail;
    }

    private void extractSessionLifecycleFromNested(V36AlertInvestigationDetailDto detail) {
        Map<String, Object> lifecycle = detail.getSessionLifecycle();
        if (lifecycle == null || lifecycle.isEmpty()) {
            return;
        }
        if (detail.getSessionEndReason() == null && lifecycle.get("sessionEndReason") instanceof String reason) {
            detail.setSessionEndReason(reason);
        }
        if (detail.getSessionEndedExplicitly() == null && lifecycle.get("sessionEndedExplicitly") instanceof Boolean explicit) {
            detail.setSessionEndedExplicitly(explicit);
        }
        if (detail.getSessionEndedAt() == null) {
            Object endedAt = lifecycle.get("sessionEndedAt");
            if (endedAt instanceof String text) {
                try {
                    detail.setSessionEndedAt(Instant.parse(text));
                } catch (Exception ignored) {}
            } else if (endedAt instanceof Instant instant) {
                detail.setSessionEndedAt(instant);
            }
        }
        if (detail.getSessionDurationMs() == null && lifecycle.get("sessionDurationMs") instanceof Number dur) {
            detail.setSessionDurationMs(dur.longValue());
        }
        if (detail.getSessionEventCount() == null && lifecycle.get("sessionEventCount") instanceof Number count) {
            detail.setSessionEventCount(count.intValue());
        }
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
            alert.setWarnings(new ArrayList<>());
        }
        return alert;
    }

    private V36LiveAlertSummaryDto hydrateAlertFields(V36LiveAlertSummaryDto alert) {
        if (alert == null) return null;
        hydrateRiskLevelFromScore(alert);
        if (!hasText(alert.getRiskLevel())) {
            alert.getWarnings().add("Risk level unavailable");
        }
        return alert;
    }

    private void hydrateRiskLevelFromScore(V36LiveAlertSummaryDto alert) {
        if (hasText(alert.getRiskLevel()) || alert.getFinalRiskScore() == null) {
            return;
        }
        double score = alert.getFinalRiskScore();
        if (score >= 80.0) {
            alert.setRiskLevel("CRITICAL");
        } else if (score >= 60.0) {
            alert.setRiskLevel("HIGH");
        } else if (score >= 35.0) {
            alert.setRiskLevel("MEDIUM");
        } else {
            alert.setRiskLevel("LOW");
        }
    }

    private List<V36LiveAlertSummaryDto> hydrateLiveAlertsFromStoredPayloads(List<V36LiveAlertSummaryDto> alerts) {
        if (alerts == null || alerts.isEmpty()) return alerts;

        List<String> eventIds = alerts.stream()
                .map(V36LiveAlertSummaryDto::getEventId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, AnomalyEvent> anomalyMap = anomalyEventRepository.findByEventIdIn(eventIds).stream()
                .collect(Collectors.toMap(
                        AnomalyEvent::getEventId,
                        a -> a,
                        (a, b) -> a.getDetectedAt() != null && a.getDetectedAt().isAfter(b.getDetectedAt()) ? a : b
                ));

        List<String> hydrated = new ArrayList<>();
        List<V36LiveAlertSummaryDto> result = new ArrayList<>(alerts.size());
        for (V36LiveAlertSummaryDto alert : alerts) {
            AnomalyEvent anomaly = anomalyMap.get(alert.getEventId());
            if (anomaly != null) {
                List<String> fields = hydrateFromAnomalyEvent(alert, anomaly);
                if (!fields.isEmpty()) {
                    hydrated.add(alert.getEventId() + " fields=" + fields);
                }
            }
            result.add(alert);
        }

        if (!hydrated.isEmpty()) {
            log.debug("ALERT_LIVE_ROW_HYDRATED {}", String.join("; ", hydrated));
        }
        return result;
    }

    private List<String> hydrateFromAnomalyEvent(V36LiveAlertSummaryDto alert, AnomalyEvent anomaly) {
        List<String> fields = new ArrayList<>();

        if (alert.getTimestamp() == null) {
            Instant ts = firstNonNull(anomaly.getEventTime(), anomaly.getDetectedAt());
            if (ts != null) {
                alert.setTimestamp(ts);
                fields.add("timestamp");
            }
        }

        if (!hasText(alert.getRiskLevel()) && hasText(anomaly.getRiskLevel())) {
            alert.setRiskLevel(anomaly.getRiskLevel());
            fields.add("riskLevel");
        }

        if (alert.getFinalRiskScore() == null) {
            Double score = firstNonNull(anomaly.getFinalRiskScore(), anomaly.getRiskScore(), anomaly.getAnomalyScore());
            if (score != null) {
                alert.setFinalRiskScore(score);
                fields.add("finalRiskScore");
            }
        }

        if (!hasText(alert.getAnomalyType()) && hasText(anomaly.getAnomalyType())) {
            alert.setAnomalyType(anomaly.getAnomalyType());
            fields.add("anomalyType");
        }

        if (alert.getAnomalyTypeConfidence() == null) {
            Double conf = firstNonNull(anomaly.getAnomalyTypeConfidence(), anomaly.getTypeConfidence());
            if (conf != null) {
                alert.setAnomalyTypeConfidence(conf);
                fields.add("anomalyTypeConfidence");
            }
        }

        if (alert.getXgboostAnomalyScore100() == null && anomaly.getXgboostAnomalyScore100() != null) {
            alert.setXgboostAnomalyScore100(anomaly.getXgboostAnomalyScore100());
            fields.add("xgboostAnomalyScore100");
        }
        if (alert.getLightgbmAlertScore100() == null && anomaly.getLightgbmAlertScore100() != null) {
            alert.setLightgbmAlertScore100(anomaly.getLightgbmAlertScore100());
            fields.add("lightgbmAlertScore100");
        }
        if (alert.getTransformerRiskScore100() == null && anomaly.getTransformerRiskScore100() != null) {
            alert.setTransformerRiskScore100(anomaly.getTransformerRiskScore100());
            fields.add("transformerRiskScore100");
        }
        if (alert.getTcnRiskScore100() == null && anomaly.getTcnRiskScore100() != null) {
            alert.setTcnRiskScore100(anomaly.getTcnRiskScore100());
            fields.add("tcnRiskScore100");
        }

        if (alert.getModelContributions() == null && hasText(anomaly.getModelContributionsJson())) {
            alert.setModelContributions(buildModelContributions(anomaly.getModelContributionsJson(), null));
            fields.add("modelContributions");
        }

        if ((alert.getTriggeredRuleCodes() == null || alert.getTriggeredRuleCodes().isEmpty())
                && hasText(anomaly.getTriggeredRulesJson())) {
            List<String> rules = parseStringList(anomaly.getTriggeredRulesJson());
            if (!rules.isEmpty()) {
                alert.setTriggeredRuleCodes(rules);
                fields.add("triggeredRuleCodes");
            }
        }

        if (alert.getChurnProbability() == null && anomaly.getChurnProbability() != null) {
            alert.setChurnProbability(anomaly.getChurnProbability());
            fields.add("churnProbability");
        }

        if (!hasText(alert.getChurnRiskLevel()) && hasText(anomaly.getChurnRiskLevel())) {
            alert.setChurnRiskLevel(anomaly.getChurnRiskLevel());
            fields.add("churnRiskLevel");
        }

        if (!hasText(alert.getPersonaLabel()) || "persona_disabled".equals(alert.getPersonaLabel())) {
            if (hasText(anomaly.getPersonaLabel()) && !"persona_disabled".equals(anomaly.getPersonaLabel())) {
                alert.setPersonaLabel(anomaly.getPersonaLabel());
                fields.add("personaLabel");
            }
        }

        if (!hasText(alert.getEventAction()) && hasText(anomaly.getEventJson())) {
            populateEventMetadataFields(alert, anomaly.getEventJson());
            if (hasText(alert.getEventAction())) fields.add("eventAction");
            if (hasText(alert.getApiTemplate())) fields.add("apiTemplate");
            if (hasText(alert.getApiFamily())) fields.add("apiFamily");
            if (hasText(alert.getController())) fields.add("controller");
            if (hasText(alert.getPage())) fields.add("page");
            if (hasText(alert.getCountry())) fields.add("country");
            if (hasText(alert.getDevice())) fields.add("device");
            if (hasText(alert.getBrowser())) fields.add("browser");
            if (hasText(alert.getOs())) fields.add("os");
            if (hasText(alert.getHttpMethod())) fields.add("httpMethod");
            if (hasText(alert.getStatus())) fields.add("status");
        }

        if (alert.getLlmEvidencePayloadAvailable() == null) {
            alert.setLlmEvidencePayloadAvailable(hasText(anomaly.getLlmExplanationEvidencePayloadJson()));
            fields.add("llmEvidencePayloadAvailable");
        }

        if (hasText(anomaly.getInvestigationPayloadJson())
                && (!hasText(alert.getEventAction()) || alert.getModelContributions() == null || alert.getTimestamp() == null)) {
            try {
                V36AlertInvestigationDetailDto detail = objectMapper.readValue(
                        anomaly.getInvestigationPayloadJson(), V36AlertInvestigationDetailDto.class);
                fields.addAll(hydrateFromInvestigationDetail(alert, detail));
            } catch (Exception ignored) {}
        }

        if (alert.getModelContributions() == null && hasText(anomaly.getLlmExplanationEvidencePayloadJson())) {
            try {
                JsonNode evidenceNode = objectMapper.readTree(anomaly.getLlmExplanationEvidencePayloadJson());
                fields.addAll(hydrateFromEvidenceNode(alert, evidenceNode));
            } catch (Exception ignored) {}
        }

        return fields;
    }

    private List<String> hydrateFromInvestigationDetail(V36LiveAlertSummaryDto alert, V36AlertInvestigationDetailDto detail) {
        List<String> fields = new ArrayList<>();
        if (detail == null) return fields;

        if (alert.getTimestamp() == null && detail.getTimestamp() != null) {
            alert.setTimestamp(detail.getTimestamp());
            fields.add("timestamp");
        }

        Map<String, Object> metadata = detail.getEventMetadata();
        if (metadata != null) {
            if (!hasText(alert.getEventAction()) && metadata.get("eventAction") instanceof String ea) {
                alert.setEventAction(ea); fields.add("eventAction");
            }
            if (!hasText(alert.getApiTemplate()) && metadata.get("apiTemplate") instanceof String api) {
                alert.setApiTemplate(api); fields.add("apiTemplate");
            }
            if (!hasText(alert.getApiFamily()) && metadata.get("apiFamily") instanceof String fam) {
                alert.setApiFamily(fam); fields.add("apiFamily");
            }
            if (!hasText(alert.getController()) && metadata.get("controller") instanceof String ctrl) {
                alert.setController(ctrl); fields.add("controller");
            }
            if (!hasText(alert.getPage()) && metadata.get("page") instanceof String p) {
                alert.setPage(p); fields.add("page");
            }
            if (!hasText(alert.getCountry()) && metadata.get("country") instanceof String cntry) {
                alert.setCountry(cntry); fields.add("country");
            }
            if (!hasText(alert.getDevice()) && metadata.get("device") instanceof String dev) {
                alert.setDevice(dev); fields.add("device");
            }
            if (!hasText(alert.getBrowser()) && metadata.get("browser") instanceof String br) {
                alert.setBrowser(br); fields.add("browser");
            }
            if (!hasText(alert.getOs()) && metadata.get("os") instanceof String os) {
                alert.setOs(os); fields.add("os");
            }
            if (!hasText(alert.getHttpMethod()) && metadata.get("httpMethod") instanceof String http) {
                alert.setHttpMethod(http); fields.add("httpMethod");
            }
            if (!hasText(alert.getStatus()) && metadata.get("status") instanceof String st) {
                alert.setStatus(st); fields.add("status");
            }
        }

        if (alert.getXgboostAnomalyScore100() == null && detail.getModelScores() != null) {
            V36ModelScoresDto scores = detail.getModelScores();
            if (scores.getXgboostAnomalyScore100() != null) { alert.setXgboostAnomalyScore100(scores.getXgboostAnomalyScore100()); fields.add("xgboostAnomalyScore100"); }
            if (scores.getLightgbmAlertScore100() != null) { alert.setLightgbmAlertScore100(scores.getLightgbmAlertScore100()); fields.add("lightgbmAlertScore100"); }
            if (scores.getTransformerRiskScore100() != null) { alert.setTransformerRiskScore100(scores.getTransformerRiskScore100()); fields.add("transformerRiskScore100"); }
            if (scores.getTcnRiskScore100() != null) { alert.setTcnRiskScore100(scores.getTcnRiskScore100()); fields.add("tcnRiskScore100"); }
        }

        if (alert.getModelContributions() == null && detail.getModelContributions() != null) {
            alert.setModelContributions(detail.getModelContributions());
            fields.add("modelContributions");
        }

        if ((alert.getTriggeredRuleCodes() == null || alert.getTriggeredRuleCodes().isEmpty())
                && detail.getTriggeredRules() != null && !detail.getTriggeredRules().isEmpty()) {
            alert.setTriggeredRuleCodes(detail.getTriggeredRules());
            fields.add("triggeredRuleCodes");
        }

        if (alert.getLlmEvidencePayloadAvailable() == null && detail.getLlmEvidencePayloadAvailable() != null) {
            alert.setLlmEvidencePayloadAvailable(detail.getLlmEvidencePayloadAvailable());
            fields.add("llmEvidencePayloadAvailable");
        }

        return fields;
    }

    private List<String> hydrateFromEvidenceNode(V36LiveAlertSummaryDto alert, JsonNode evidenceNode) {
        List<String> fields = new ArrayList<>();
        if (evidenceNode == null) return fields;

        JsonNode eventMeta = evidenceNode.path("eventMetadata");
        if (!eventMeta.isMissingNode() && eventMeta.isObject()) {
            if (!hasText(alert.getEventAction())) {
                String ea = textAt(eventMeta, "eventAction");
                if (ea != null) { alert.setEventAction(ea); fields.add("eventAction"); }
            }
            if (!hasText(alert.getApiTemplate())) {
                String api = textAt(eventMeta, "apiTemplate");
                if (api != null) { alert.setApiTemplate(api); fields.add("apiTemplate"); }
            }
            if (!hasText(alert.getApiFamily())) {
                String fam = textAt(eventMeta, "apiFamily");
                if (fam != null) { alert.setApiFamily(fam); fields.add("apiFamily"); }
            }
            if (!hasText(alert.getController())) {
                String ctrl = textAt(eventMeta, "controller");
                if (ctrl != null) { alert.setController(ctrl); fields.add("controller"); }
            }
            if (!hasText(alert.getPage())) {
                String p = textAt(eventMeta, "page");
                if (p != null) { alert.setPage(p); fields.add("page"); }
            }
            if (!hasText(alert.getCountry())) {
                String c = textAt(eventMeta, "country");
                if (c != null) { alert.setCountry(c); fields.add("country"); }
            }
            if (!hasText(alert.getDevice())) {
                String d = textAt(eventMeta, "device");
                if (d != null) { alert.setDevice(d); fields.add("device"); }
            }
            if (!hasText(alert.getBrowser())) {
                String b = textAt(eventMeta, "browser");
                if (b != null) { alert.setBrowser(b); fields.add("browser"); }
            }
            if (!hasText(alert.getOs())) {
                String os = textAt(eventMeta, "os");
                if (os != null) { alert.setOs(os); fields.add("os"); }
            }
            if (!hasText(alert.getHttpMethod())) {
                String http = textAt(eventMeta, "httpMethod");
                if (http != null) { alert.setHttpMethod(http); fields.add("httpMethod"); }
            }
            if (!hasText(alert.getStatus())) {
                String s = textAt(eventMeta, "status");
                if (s != null) { alert.setStatus(s); fields.add("status"); }
            }
        }

        JsonNode modelScores = evidenceNode.path("modelScores");
        if (!modelScores.isMissingNode() && modelScores.isObject()) {
            if (alert.getXgboostAnomalyScore100() == null && modelScores.has("xgboostAnomalyScore100")) {
                alert.setXgboostAnomalyScore100(modelScores.get("xgboostAnomalyScore100").asDouble());
                fields.add("xgboostAnomalyScore100");
            }
            if (alert.getLightgbmAlertScore100() == null && modelScores.has("lightgbmAlertScore100")) {
                alert.setLightgbmAlertScore100(modelScores.get("lightgbmAlertScore100").asDouble());
                fields.add("lightgbmAlertScore100");
            }
            if (alert.getTransformerRiskScore100() == null && modelScores.has("transformerRiskScore100")) {
                alert.setTransformerRiskScore100(modelScores.get("transformerRiskScore100").asDouble());
                fields.add("transformerRiskScore100");
            }
            if (alert.getTcnRiskScore100() == null && modelScores.has("tcnRiskScore100")) {
                alert.setTcnRiskScore100(modelScores.get("tcnRiskScore100").asDouble());
                fields.add("tcnRiskScore100");
            }
        }

        JsonNode contributions = evidenceNode.path("modelContributions");
        if (alert.getModelContributions() == null && !contributions.isMissingNode() && contributions.isObject()) {
            alert.setModelContributions(objectMapper.convertValue(contributions, V36ModelContributionsDto.class));
            fields.add("modelContributions");
        }

        if (alert.getLlmEvidencePayloadAvailable() == null) {
            alert.setLlmEvidencePayloadAvailable(true);
            fields.add("llmEvidencePayloadAvailable");
        }

        return fields;
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
