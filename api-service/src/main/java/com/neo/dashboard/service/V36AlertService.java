package com.neo.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.ApiPageResponse;
import com.neo.dashboard.dto.v36.V36AlertInvestigationDetailDto;
import com.neo.dashboard.dto.v36.V36AnomalyTypeAttributionDto;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service that provides V3.6.1 alert data for live-alert lists, critical alerts,
 * user-scoped alerts, and alert investigation details.  Reads from Redis ZSETs
 * first (with payload hydration from separate payload keys), falls back to SQL
 * snapshot tables, then to the anomaly-event repository.  Enriches investigation
 * details with evidence, session lifecycle, next-event predictions, and data
 * integrity guards to prevent cross-event data leakage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class V36AlertService {

    /** Reads structured data from Redis caches (raw, JSON, typed values, ZSET items). */
    private final V36RedisReadService redisReadService;
    /** JPA repository for anomaly events, used for SQL fallback queries. */
    private final AnomalyEventRepository anomalyEventRepository;
    /** JPA repository for session analysis, used for enrichment and fallback. */
    private final SessionAnalysisRepository sessionAnalysisRepository;
    /** Jackson mapper for JSON parsing, tree-to-value, and type-safe conversion. */
    private final ObjectMapper objectMapper;
    /** Reads dashboard snapshots from Redis with fallback to SQL snapshot tables. */
    private final DashboardSnapshotFallbackService snapshotFallbackService;
    /** Reads LLM evidence payloads from Redis for hydrating investigation details. */
    private final LlmEvidenceReadService evidenceReadService;
    /** Resolves next-event predictions attached to investigation details. */
    private final V36NextEventPredictionService nextEventPredictionService;

    /**
     * Returns a paginated list of live alerts, optionally filtered by risk
     * level, anomaly type, insured ID, session ID, and time range.
     *
     * @param riskLevel   optional risk-level filter (e.g. "CRITICAL", "HIGH")
     * @param anomalyType optional anomaly-type filter
     * @param insuredId   optional insured-entity filter
     * @param sessionId   optional session filter
     * @param from        optional start of the time range (inclusive)
     * @param to          optional end of the time range (inclusive)
     * @param limit       maximum number of items per page
     * @param offset      pagination offset
     * @return a paginated response of live-alert summaries
     */
    public ApiPageResponse<V36LiveAlertSummaryDto> getLiveAlerts(String riskLevel,
                                                                    String anomalyType,
                                                                    String insuredId,
                                                                    String sessionId,
                                                                    Instant from,
                                                                    Instant to,
                                                                    int limit,
                                                                    int offset) {
        return queryLiveAlerts(riskLevel, anomalyType, insuredId, sessionId, from, to, limit, offset);
    }

    /**
     * Returns a paginated list of critical alerts only.  Delegates to
     * {@link #queryLiveAlerts} with the risk level pre-set to "CRITICAL".
     *
     * @param limit  maximum number of items per page
     * @param offset pagination offset
     * @return a paginated response of critical-alert summaries
     */
    public ApiPageResponse<V36LiveAlertSummaryDto> getCriticalAlerts(int limit, int offset) {
        return queryLiveAlerts("CRITICAL", null, null, null, null, null, limit, offset);
    }

    /**
     * Core query method for live alerts.  Tries three data sources in order:
     * <ol>
     *   <li>Redis canonical ZSET with payload-key hydration</li>
     *   <li>SQL snapshot tables</li>
     *   <li>Direct anomaly-event repository queries</li>
     * </ol>
     * Results are merged with critical alerts, sorted by timestamp descending,
     * filtered by the given criteria, and paginated.
     */
    private ApiPageResponse<V36LiveAlertSummaryDto> queryLiveAlerts(String riskLevel,
                                                                        String anomalyType,
                                                                        String insuredId,
                                                                        String sessionId,
                                                                        Instant from,
                                                                        Instant to,
                                                                        int limit,
                                                                        int offset) {
        /* Priority 1: Redis canonical ZSET with payload hydration. */
        List<V36LiveAlertSummaryDto> redisAlerts = readCanonicalAlertList(
                CacheKeys.ALERTS_LIVE_V36_ZSET,
                CacheKeys.ALERTS_LIVE_V36,
                CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX,
                5000);
        if (!redisAlerts.isEmpty()) {
            /* Merge in any critical alerts that are not already in the live set. */
            List<V36LiveAlertSummaryDto> merged = mergeCriticalAlerts(redisAlerts);
            List<V36LiveAlertSummaryDto> sorted = sortByTimestampDesc(merged);
            List<V36LiveAlertSummaryDto> filtered = filterAlerts(sorted, riskLevel, anomalyType, insuredId, sessionId, from, to);
            return buildPageResponse(filtered, limit, offset);
        }

        /* Priority 2: SQL snapshot tables (fallback when Redis is empty). */
        List<V36LiveAlertSummaryDto> snapshotAlerts = snapshotFallbackService
                .readListFromSql(V36LiveAlertSummaryDto.class, "alerts", "alerts:latest", 500);
        if (!snapshotAlerts.isEmpty()) {
            List<V36LiveAlertSummaryDto> processed = snapshotAlerts.stream()
                    .peek(a -> { if (!hasText(a.getSource())) a.setSource("sql_fallback"); })
                    .map(this::normalizeAlert)
                    .map(this::hydrateAlertFields)
                    .toList();
            List<V36LiveAlertSummaryDto> deduped = deduplicateByEventIdWithRichness(processed);
            List<V36LiveAlertSummaryDto> hydrated = hydrateLiveAlertsFromStoredPayloads(deduped);
            List<V36LiveAlertSummaryDto> sorted = sortByTimestampDesc(hydrated);
            List<V36LiveAlertSummaryDto> filtered = filterAlerts(sorted, riskLevel, anomalyType, insuredId, sessionId, from, to);
            return buildPageResponse(filtered, limit, offset);
        }

        /* Priority 3: Direct SQL queries against the anomaly_event table. */
        return getSqlAlerts(riskLevel, anomalyType, insuredId, sessionId, from, to, limit, offset);
    }

    /**
     * Merges critical-alert entries from the dedicated critical ZSET into the
     * live-alert list when they are not already present.  This ensures that
     * critical alerts are always surfaced even if they haven't been replicated
     * to the live ZSET.
     *
     * @param candidates the live-alert list from the canonical live ZSET
     * @return a new list containing both the original candidates and any missing critical alerts
     */
    private List<V36LiveAlertSummaryDto> mergeCriticalAlerts(List<V36LiveAlertSummaryDto> candidates) {
        List<V36LiveAlertSummaryDto> criticalAlerts = readCanonicalAlertList(
                CacheKeys.ALERTS_CRITICAL_V36_ZSET,
                CacheKeys.ALERTS_CRITICAL_V36,
                CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX,
                5000);
        if (criticalAlerts.isEmpty()) {
            return candidates;
        }

        /* Build a set of event IDs already present in the live candidates. */
        Set<String> candidateEventIds = candidates.stream()
                .map(V36LiveAlertSummaryDto::getEventId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        /* Find critical alerts whose event ID is not already in the live list. */
        List<V36LiveAlertSummaryDto> missing = criticalAlerts.stream()
                .filter(a -> a.getEventId() == null || !candidateEventIds.contains(a.getEventId()))
                .toList();

        if (missing.isEmpty()) {
            return candidates;
        }

        log.warn("CRITICAL_ALERTS_MERGED_INTO_LIVE mergedCount={} liveCount={} criticalCount={}",
                missing.size(), candidates.size(), criticalAlerts.size());
        log.warn("Missing critical eventIds: {}",
                missing.stream().map(V36LiveAlertSummaryDto::getEventId).toList());

        List<V36LiveAlertSummaryDto> merged = new ArrayList<>(candidates);
        merged.addAll(missing);
        return merged;
    }

    /**
     * Reads alert items from the canonical Redis ZSET key with per-member
     * payload hydration.  Falls back to the legacy Redis list key when the
     * ZSET is empty or unavailable.  Deduplicates, processes, and hydrates
     * the result before returning.
     *
     * @param zsetKey          the canonical Redis ZSET key
     * @param legacyListKey    the legacy Redis list key for fallback
     * @param payloadKeyPrefix the prefix for per-event payload keys
     * @param maxFetch         maximum number of ZSET members to fetch
     * @return a list of fully-processed alert summaries
     */
    private List<V36LiveAlertSummaryDto> readCanonicalAlertList(String zsetKey, String legacyListKey,
                                                                   String payloadKeyPrefix, int maxFetch) {
        /* Try the canonical ZSET first. */
        List<V36LiveAlertSummaryDto> zsetAlerts = redisReadService.readZSetAlertItems(
                zsetKey, payloadKeyPrefix, V36LiveAlertSummaryDto.class, maxFetch);
        if (!zsetAlerts.isEmpty()) {
            List<V36LiveAlertSummaryDto> processed = processAlertList(zsetAlerts, "redis_zset");
            List<V36LiveAlertSummaryDto> deduped = deduplicateByEventIdWithRichness(processed);
            List<V36LiveAlertSummaryDto> hydrated = hydrateLiveAlertsFromStoredPayloads(deduped);
            logAlertRedisDiagnostics(zsetKey, legacyListKey, zsetAlerts.size(), true);
            return hydrated;
        }

        /* Fall back to the legacy list key. */
        List<V36LiveAlertSummaryDto> legacyAlerts = readAlertList(legacyListKey, maxFetch);
        if (!legacyAlerts.isEmpty()) {
            log.warn("CANONICAL_ZSET_EMPTY_LEGACY_LIST_USED zsetKey={} legacyListKey={} legacyCount={}",
                    zsetKey, legacyListKey, legacyAlerts.size());
            legacyAlerts.forEach(a -> {
                a.setSource("redis_legacy_list");
                a.getWarnings().add("canonical_zset_empty_legacy_list_used");
            });
            logAlertRedisDiagnostics(zsetKey, legacyListKey, 0, false);
            return legacyAlerts;
        }

        logAlertRedisDiagnostics(zsetKey, legacyListKey, 0, false);
        return List.of();
    }

    /**
     * Processes a raw list of alert summaries by setting the source marker,
     * normalising fields, and hydrating risk-level from scores where missing.
     *
     * @param alerts the raw alert list
     * @param source the source string to assign (e.g. "redis_zset")
     * @return the processed list
     */
    private List<V36LiveAlertSummaryDto> processAlertList(List<V36LiveAlertSummaryDto> alerts, String source) {
        if (alerts == null || alerts.isEmpty()) return alerts;
        return alerts.stream()
                .peek(a -> { if (!hasText(a.getSource())) a.setSource(source); })
                .map(this::normalizeAlert)
                .map(this::hydrateAlertFields)
                .toList();
    }

    /**
     * Logs diagnostic information about Redis alert sources (ZSET vs legacy
     * list) at DEBUG level for operational monitoring.
     */
    private void logAlertRedisDiagnostics(String zsetKey, String legacyListKey, int zsetCount, boolean canonicalUsed) {
        if (!log.isDebugEnabled()) return;
        try {
            Long legacyCount = redisReadService.countItems(legacyListKey);
            log.debug("ALERT_REDIS_SOURCES zsetKey={} zsetCount={} legacyListKey={} legacyCount={} canonicalUsed={}",
                    zsetKey, zsetCount, legacyListKey, legacyCount, canonicalUsed);
        } catch (Exception e) {
            log.debug("ALERT_REDIS_SOURCES diagnostics failed", e);
        }
    }

    /**
     * Returns a paginated list of alerts for a specific insured user.
     * Reads from the user-scoped Redis ZSET first; falls back to SQL queries.
     *
     * @param insuredId the insured entity to filter by
     * @param riskLevel optional risk-level filter
     * @param from      optional start of time range
     * @param to        optional end of time range
     * @param limit     maximum items per page
     * @param offset    pagination offset
     * @return a paginated response of user-scoped alert summaries
     */
    public ApiPageResponse<V36LiveAlertSummaryDto> getUserAlerts(String insuredId,
                                                                    String riskLevel,
                                                                    Instant from,
                                                                    Instant to,
                                                                    int limit,
                                                                    int offset) {
        List<V36LiveAlertSummaryDto> redisAlerts = readCanonicalAlertList(
                CacheKeys.userAlertsZSetKey(insuredId),
                CacheKeys.userAlertsKey(insuredId),
                CacheKeys.ALERT_LIVE_V36_PAYLOAD_PREFIX,
                5000);
        if (!redisAlerts.isEmpty()) {
            List<V36LiveAlertSummaryDto> sorted = sortByTimestampDesc(redisAlerts);
            List<V36LiveAlertSummaryDto> filtered = filterAlerts(sorted, riskLevel, null, insuredId, null, from, to);
            return buildPageResponse(filtered, limit, offset);
        }
        return getSqlAlerts(riskLevel, null, insuredId, null, from, to, limit, offset);
    }

    /**
     * Returns the full investigation detail for a given alert event ID.
     * Tries the following sources in order:
     * <ol>
     *   <li>Redis investigation payload</li>
     *   <li>SQL anomaly event's investigation payload (exact match)</li>
     *   <li>SQL session analysis payload (exact event match)</li>
     *   <li>Constructed from raw anomaly-event and session-analysis entity fields</li>
     * </ol>
     * Also enriches with evidence, session lifecycle, next-event prediction,
     * and applies hard guards to prevent cross-event data leakage.
     *
     * @param eventId the event identifier to look up
     * @return a fully-populated investigation detail DTO
     * @throws ApiException with 404 NOT_FOUND when the event is not in the database
     */
    @Transactional(readOnly = true)
    public V36AlertInvestigationDetailDto getAlertDetail(String eventId) {
        Optional<V36AlertInvestigationDetailDto> redisDetail = redisReadService
                .readValue(CacheKeys.alertInvestigationKey(eventId), V36AlertInvestigationDetailDto.class)
                .filter(d -> eventId.equals(d.getEventId()) || eventId.equals(d.getRecordId()))
                .filter(d -> isDetailForRequestedEvent(d, eventId))
                .map(this::normalizeInvestigation)
                .map(detail -> hydrateEvidenceFields(detail, eventId));
        if (redisDetail.isPresent()) {
            V36AlertInvestigationDetailDto detail = redisDetail.get();
            AnomalyEvent anomaly = anomalyEventRepository
                    .findTopByEventIdOrderByDetectedAtDesc(eventId).orElse(null);
            enrichSessionLifecycle(detail, anomaly, null);
            enrichTimestamp(detail, anomaly, eventId);
            enrichNextEventPrediction(detail, anomaly);
            applyHardGuards(detail, eventId);
            return detail;
        }

        AnomalyEvent anomaly = anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Alert not found"));

        V36AlertInvestigationDetailDto detail;
        SessionAnalysis session = null;
        String rawInvestigationJson = anomaly.getInvestigationPayloadJson();
        boolean payloadForEvent = false;
        if (hasText(rawInvestigationJson)) {
            JsonNode rawNode = parseJson(rawInvestigationJson);
            payloadForEvent = rawNode != null && rawNode.isObject() && isPayloadForRequestedEvent(rawNode, eventId, eventId);
        }
        if (payloadForEvent) {
            Optional<V36AlertInvestigationDetailDto> eventPayload = parseInvestigationPayload(rawInvestigationJson);
            if (eventPayload.isPresent()) {
                detail = eventPayload.get();
                if (!hasText(detail.getSource())) {
                    detail.setSource("sql-payload");
                }
                if (detail.getId() == null) {
                    detail.setId(anomaly.getId());
                }
                if (detail.getAnomalyDbId() == null) {
                    detail.setAnomalyDbId(anomaly.getId());
                }
                detail = hydrateEvidenceFields(hydrateDetailTimestamp(normalizeInvestigation(detail), anomaly), eventId);
            } else {
                detail = buildInvestigationFromSql(anomaly, null);
                detail.addWarning("exact_event_payload_unavailable");
                detail = hydrateEvidenceFields(detail, eventId);
                enrichFromLivePayloadIfNeeded(detail, eventId, anomaly);
                enrichTimestamp(detail, anomaly, eventId);
                enrichSessionLifecycle(detail, anomaly, null);
                enrichNextEventPrediction(detail, anomaly);
                applyHardGuards(detail, eventId);
                return detail;
            }
        } else if (hasText(rawInvestigationJson)) {
            detail = buildInvestigationFromSql(anomaly, null);
            detail.addWarning("sql_payload_event_mismatch");
            detail = hydrateEvidenceFields(detail, eventId);
            enrichFromLivePayloadIfNeeded(detail, eventId, anomaly);
            enrichTimestamp(detail, anomaly, eventId);
            enrichSessionLifecycle(detail, anomaly, null);
            enrichNextEventPrediction(detail, anomaly);
            applyHardGuards(detail, eventId);
            return detail;
        } else {
            session = sessionAnalysisRepository
                    .findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anomaly.getInsuredId(), anomaly.getSessionId())
                    .orElse(null);
            if (session != null) {
                String sessionInvestigationJson = session.getInvestigationPayloadJson();
                boolean sessionPayloadForEvent = false;
                if (hasText(sessionInvestigationJson)) {
                    JsonNode sessionRawNode = parseJson(sessionInvestigationJson);
                    sessionPayloadForEvent = sessionRawNode != null && sessionRawNode.isObject() && isPayloadForRequestedEvent(sessionRawNode, eventId, eventId);
                }
                if (sessionPayloadForEvent) {
                    Optional<V36AlertInvestigationDetailDto> sessionPayload = parseInvestigationPayload(sessionInvestigationJson);
                    if (sessionPayload.isPresent()) {
                        detail = sessionPayload.get();
                        if (!hasText(detail.getSource())) {
                            detail.setSource("sql-payload");
                        }
                        if (detail.getId() == null) {
                            detail.setId(anomaly.getId());
                        }
                        if (detail.getAnomalyDbId() == null) {
                            detail.setAnomalyDbId(anomaly.getId());
                        }
                        detail = hydrateEvidenceFields(hydrateDetailTimestamp(normalizeInvestigation(detail), anomaly), eventId);
                    } else {
                        detail = buildInvestigationFromSql(anomaly, session);
                        detail = hydrateEvidenceFields(detail, eventId);
                    }
                } else if (hasText(sessionInvestigationJson)) {
                    detail = buildInvestigationFromSql(anomaly, session);
                    detail.addWarning("sql_payload_event_mismatch");
                    detail = hydrateEvidenceFields(detail, eventId);
                } else {
                    detail = buildInvestigationFromSql(anomaly, session);
                    detail = hydrateEvidenceFields(detail, eventId);
                }
            } else {
                detail = buildInvestigationFromSql(anomaly, null);
                detail.addWarning("exact_event_payload_unavailable");
                detail = hydrateEvidenceFields(detail, eventId);
            }
        }
        enrichTimestamp(detail, anomaly, eventId);
        enrichSessionLifecycle(detail, anomaly, session);
        enrichFromLivePayloadIfNeeded(detail, eventId, anomaly);
        enrichNextEventPrediction(detail, anomaly);
        applyHardGuards(detail, eventId);
        return detail;
    }

    /**
     * Applies response-level data-integrity guards to prevent cross-event data
     * leakage.  Checks three areas:
     * <ul>
     *   <li>{@code eventMetadata.eventId} must match the requested event</li>
     *   <li>{@code anomalyTypeAttribution.evidence.finalRiskScore} must match the detail's score</li>
     *   <li>{@code nextEventPredictionEvidence} must reference the correct context</li>
     * </ul>
     *
     * @param detail  the investigation detail to guard
     * @param eventId the requested event identifier
     */
    private void applyHardGuards(V36AlertInvestigationDetailDto detail, String eventId) {
        if (detail == null) return;

        /* Hard guard: never return mismatched eventMetadata.eventId. */
        if (detail.getEventMetadata() != null) {
            Object metaId = detail.getEventMetadata().get("eventId");
            if (metaId instanceof String && !eventId.equals(metaId) && !Objects.equals(detail.getRecordId(), metaId)) {
                detail.setEventMetadata(buildPartialEventMetadataFromSql(detail));
                detail.addWarning("event_metadata_event_mismatch_suppressed");
                if ("sql-payload".equals(detail.getSource())) {
                    detail.setSource("sql");
                }
            }
        }

        /* Hard guard: anomalyTypeAttribution evidence must not reference a different event. */
        if (detail.getAnomalyTypeAttribution() != null && detail.getAnomalyTypeAttribution().getEvidence() != null) {
            Map<String, Object> evidence = detail.getAnomalyTypeAttribution().getEvidence();
            Object scoreInEvidence = evidence.get("finalRiskScore");
            if (scoreInEvidence instanceof Number && detail.getFinalRiskScore() != null
                    && Math.abs(((Number) scoreInEvidence).doubleValue() - detail.getFinalRiskScore()) > 0.001) {
                detail.getAnomalyTypeAttribution().setEvidence(null);
                detail.addWarning("anomaly_type_attribution_event_mismatch");
            }
        }

        /* Hard guard: nextEventPredictionEvidence context must match the requested event. */
        if (detail.getNextEventPredictionEvidence() != null && eventId != null) {
            Map<String, Object> predictionEv = detail.getNextEventPredictionEvidence();
            if (predictionEv.containsKey("prediction")) {
                try {
                    V36NextEventPredictionDto prediction = objectMapper.convertValue(
                            predictionEv.get("prediction"), V36NextEventPredictionDto.class);
                    if (!isPredictionMatchingEvent(prediction, eventId)) {
                        detail.setNextEventPredictionEvidence(null);
                        detail.addWarning("next_event_prediction_context_mismatch");
                    }
                } catch (Exception e) {
                    detail.setNextEventPredictionEvidence(null);
                    detail.addWarning("next_event_prediction_context_mismatch");
                }
            }
        }
    }

    /**
     * Builds a safe, minimal event-metadata map from the detail's own fields,
     * used when the original metadata contains a mismatching event ID.
     *
     * @param detail the investigation detail
     * @return a map with only eventId, recordId, and timestamp keys
     */
    private Map<String, Object> buildPartialEventMetadataFromSql(V36AlertInvestigationDetailDto detail) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (detail.getEventId() != null) meta.put("eventId", detail.getEventId());
        if (detail.getRecordId() != null) meta.put("recordId", detail.getRecordId());
        if (detail.getTimestamp() != null) meta.put("timestamp", detail.getTimestamp().toString());
        return meta;
    }

    /**
     * Hydrates the investigation detail's evidence fields (sequence, tabular,
     * rule, churn, forecast, attribution, model scores, model contributions,
     * event metadata) from the LLM evidence payload when they are null.
     *
     * @param detail  the investigation detail to hydrate
     * @param eventId the event identifier for fetching evidence
     * @return the same detail instance with any null evidence fields populated
     */
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
                    /* Only set fields that are still null on the detail. */
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

    /**
     * Sets the investigation detail's timestamp from the anomaly event when the
     * detail's own timestamp is null.
     */
    private V36AlertInvestigationDetailDto hydrateDetailTimestamp(V36AlertInvestigationDetailDto detail, AnomalyEvent anomaly) {
        if (detail != null && detail.getTimestamp() == null && anomaly != null) {
            detail.setTimestamp(firstNonNull(anomaly.getEventTime(), anomaly.getDetectedAt()));
        }
        return detail;
    }

    /**
     * Reads alert summaries from a Redis list key, normalises, deduplicates,
     * hydrates, and enriches them with stored payload data.
     *
     * @param key   the Redis list key
     * @param limit maximum items to read
     * @return a fully-processed list of alert summaries
     */
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

    /**
     * Deduplicates a list of alert summaries by event ID, keeping the entry
     * with the highest "richness score" (most populated fields).  Logs a
     * warning when duplicates are found.
     *
     * @param alerts the raw alert list (possibly with duplicates)
     * @return a deduplicated list preserving the richest entry per event ID
     */
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

    /**
     * Computes a "richness" score for an alert summary based on how many of
     * its fields are populated.  Used to select the best duplicate when
     * multiple rows exist for the same event ID.
     *
     * @param a the alert summary to score
     * @return an integer score where higher means richer
     */
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

    /**
     * Queries alert summaries directly from the anomaly_event database table
     * with pagination and optional filters.
     */
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
                Sort.by(Sort.Direction.DESC, "eventTime", "id")
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
        return ApiPageResponse.of(items, normalizeLimit(limit), offset, (int) page.getTotalElements());
    }

    /**
     * Sorts a list of alert summaries by timestamp descending, then by
     * createdAt descending, then by eventId descending as tiebreakers.
     */
    private List<V36LiveAlertSummaryDto> sortByTimestampDesc(List<V36LiveAlertSummaryDto> alerts) {
        if (alerts == null || alerts.size() <= 1) return alerts;
        return alerts.stream()
                .sorted(Comparator
                        .comparing(V36LiveAlertSummaryDto::getTimestamp,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(V36LiveAlertSummaryDto::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(V36LiveAlertSummaryDto::getEventId,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                )
                .toList();
    }

    /**
     * Paginates a candidate list: slices a sublist based on offset + limit and
     * wraps it in an {@link ApiPageResponse} with the total count.
     */
    private ApiPageResponse<V36LiveAlertSummaryDto> buildPageResponse(List<V36LiveAlertSummaryDto> candidates, int limit, int offset) {
        int normalizedLimit = normalizeLimit(limit);
        int normalizedOffset = Math.max(offset, 0);
        int totalCount = candidates == null ? 0 : candidates.size();
        if (candidates == null || candidates.isEmpty() || normalizedOffset >= totalCount) {
            return ApiPageResponse.of(List.of(), normalizedLimit, normalizedOffset, totalCount);
        }
        int end = Math.min(normalizedOffset + normalizedLimit, totalCount);
        return ApiPageResponse.of(candidates.subList(normalizedOffset, end), normalizedLimit, normalizedOffset, totalCount);
    }

    /**
     * Filters a list of alert summaries by the given optional criteria using
     * case-insensitive matching.  Fields are matched with {@link #matches}.
     */
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

    /**
     * Converts an {@link AnomalyEvent} entity into a {@link V36LiveAlertSummaryDto}
     * by mapping all relevant fields, populating event-metadata from the event
     * JSON, and attempting to hydrate from investigation payload, LLM evidence,
     * or session data when primary fields are missing.
     */
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

        if (!hasText(dto.getEventAction()) && hasText(anomaly.getInvestigationPayloadJson())) {
            try {
                V36AlertInvestigationDetailDto detail = objectMapper.readValue(
                        anomaly.getInvestigationPayloadJson(), V36AlertInvestigationDetailDto.class);
                hydrateFromInvestigationDetail(dto, detail);
            } catch (Exception ignored) {}
        }

        if (!hasText(dto.getEventAction()) && hasText(anomaly.getLlmExplanationEvidencePayloadJson())) {
            try {
                JsonNode evidenceNode = objectMapper.readTree(anomaly.getLlmExplanationEvidencePayloadJson());
                hydrateFromEvidenceNode(dto, evidenceNode);
            } catch (Exception ignored) {}
        }

        if (!hasText(dto.getEventAction()) && hasText(anomaly.getSessionId())) {
            tryHydrateFromSession(dto, anomaly);
        }

        return normalizeAlert(dto);
    }

    /**
     * Attempts to hydrate a live alert summary with data from the session
     * analysis associated with the same anomaly event.  Checks the session's
     * investigation payload and LLM evidence payload.
     */
    private void tryHydrateFromSession(V36LiveAlertSummaryDto dto, AnomalyEvent anomaly) {
        String eventId = anomaly.getEventId();
        sessionAnalysisRepository
                .findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anomaly.getInsuredId(), anomaly.getSessionId())
                .ifPresent(session -> {
                    if (hasText(session.getInvestigationPayloadJson())) {
                        try {
                            V36AlertInvestigationDetailDto sessionDetail = objectMapper.readValue(
                                    session.getInvestigationPayloadJson(), V36AlertInvestigationDetailDto.class);
                            if (sessionDetail.getEventId() != null && sessionDetail.getEventId().equals(eventId)) {
                                hydrateFromInvestigationDetail(dto, sessionDetail);
                            }
                        } catch (Exception ignored) {}
                    }

                    if (!hasText(dto.getEventAction()) && hasText(session.getLlmExplanationEvidencePayloadJson())) {
                        try {
                            JsonNode evNode = objectMapper.readTree(session.getLlmExplanationEvidencePayloadJson());
                            String payloadEventId = textAt(evNode, "eventId");
                            if (payloadEventId != null && payloadEventId.equals(eventId)) {
                                hydrateFromEvidenceNode(dto, evNode);
                            }
                        } catch (Exception ignored) {}
                    }

                    if (dto.getLlmEvidencePayloadAvailable() == null || !dto.getLlmEvidencePayloadAvailable()) {
                        dto.setLlmEvidencePayloadAvailable(hasText(session.getLlmExplanationEvidencePayloadJson()));
                    }
                });
    }

    /**
     * Populates event-metadata fields (eventAction, apiTemplate, apiFamily,
     * controller, page, country, device, browser, os, httpMethod, status) on
     * the DTO by extracting them from the event's JSON payload.  Skips fields
     * that are already set.
     */
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

    /**
     * Builds an investigation detail from an {@link AnomalyEvent} entity and an
     * optional {@link SessionAnalysis}.  Maps all entity columns, reads the raw
     * investigation payload (only from the anomaly, never from the session),
     * extracts session lifecycle from available payloads, and constructs evidence
     * DTOs (model scores, model contributions, sequence, tabular, rule,
     * attribution, churn, forecast, persona).  Applies event-scoping guards to
     * prevent cross-event data leakage.
     *
     * @param anomaly the anomaly event entity (required)
     * @param session the associated session analysis (may be null)
     * @return a fully-constructed investigation detail with source set to "sql"
     */
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
        detail.setEventMetadata(buildEventMetadataFromEventJson(anomaly.getEventJson()));
        // Validate eventMetadata.eventId matches the requested event
        if (detail.getEventMetadata() != null) {
            Object metaEventId = detail.getEventMetadata().get("eventId");
            if (metaEventId instanceof String metaId && !anomaly.getEventId().equals(metaId)) {
                detail.setEventMetadata(null);
                detail.addWarning("sql_payload_event_mismatch");
            }
        }
        detail.setSource("sql");

        // Raw payload: only from anomaly's investigationPayloadJson, verify eventId + eventMetadata.eventId match
        // Never use session.getInvestigationPayloadJson() - belongs to session-finalization event
        boolean hasExactPayload = false;
        boolean hasMismatchedRawPayload = false;
        if (hasText(anomaly.getInvestigationPayloadJson())) {
            Map<String, Object> parsed = parseObjectMap(anomaly.getInvestigationPayloadJson());
            if (!parsed.isEmpty()) {
                String parsedEventId = mapText(parsed, "eventId");
                if (parsedEventId != null && anomaly.getEventId().equals(parsedEventId) && isRawPayloadForEvent(parsed, anomaly.getEventId())) {
                    detail.setRawPayload(parsed);
                    hasExactPayload = true;
                } else {
                    hasMismatchedRawPayload = true;
                }
            }
        }

        // Extract session lifecycle from any available payload (session-level, not event-scoped)
        extractLifecycleFromPayload(detail, anomaly.getInvestigationPayloadJson());
        extractLifecycleFromPayload(detail, session == null ? null : session.getInvestigationPayloadJson());

        // Explicit session-level fields
        if (session != null && session.getSessionDurationSeconds() != null) {
            detail.setSessionDurationMs(session.getSessionDurationSeconds() * 1000L);
        }
        if (session != null && session.getTotalEvents() != null) {
            detail.setSessionEventCount(session.getTotalEvents());
        }

        // Evidence: only use session data if exact payload is available
        // Prevents session-level data leaking as event-level evidence
        SessionAnalysis evidenceSession = hasExactPayload ? session : null;

        detail.setModelScores(buildModelScores(anomaly, evidenceSession));
        detail.setModelContributions(buildModelContributions(anomaly.getModelContributionsJson(), evidenceSession == null ? null : evidenceSession.getModelContributionsJson()));
        detail.setSequenceEvidence(buildSequenceEvidence(evidenceSession));
        detail.setTabularEvidence(buildTabularEvidence(evidenceSession));
        detail.setRuleEvidence(buildRuleEvidence(anomaly, evidenceSession));
        detail.setAnomalyTypeAttribution(buildAttribution(anomaly, evidenceSession));
        detail.setChurnContext(buildChurnContext(anomaly, evidenceSession));
        detail.setForecastContext(buildForecastContext(evidenceSession));
        detail.setPersona(buildPersona(evidenceSession, anomaly));
        detail.setRuntimeWarnings(parseStringList(firstText(anomaly.getRuntimeWarningsJson(), evidenceSession == null ? null : evidenceSession.getWarningsJson())));
        detail.setLlmEvidencePayloadAvailable(hasText(anomaly.getLlmExplanationEvidencePayloadJson())
                || (evidenceSession != null && hasText(evidenceSession.getLlmExplanationEvidencePayloadJson())));
        detail.setLlmEvidenceRedisKey(CacheKeys.alertLlmEvidenceKey(anomaly.getEventId()));

        if (hasMismatchedRawPayload) {
            detail.addWarning("raw_payload_event_mismatch");
        }
        guardEventScoping(detail, anomaly, session);
        return normalizeInvestigation(detail);
    }

    /**
     * Builds a {@link V36ModelScoresDto} by merging anomaly and session model
     * scores, preferring the anomaly value when available.
     */
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

    /**
     * Parses model contributions from either the anomaly's or session's
     * model-contributions JSON, preferring the first non-null value.
     */
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

    /**
     * Builds sequence-evidence DTO from a session analysis, or returns
     * {@code null} when no session is available.
     */
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

    /**
     * Builds tabular-evidence DTO from a session analysis, or returns
     * {@code null} when no session is available.
     */
    private V36TabularEvidenceDto buildTabularEvidence(SessionAnalysis session) {
        if (session == null) {
            return null;
        }
        V36TabularEvidenceDto dto = new V36TabularEvidenceDto();
        dto.setFeatureWarnings(parseObjectMap(session.getWarningsJson()));
        dto.setRaw(parseObjectMap(session.getModelArtifactsJson()));
        return dto;
    }

    /**
     * Builds rule-evidence DTO from anomaly and optional session data,
     * merging triggered rules, risk score, and rule contributions.
     */
    private V36RuleEvidenceDto buildRuleEvidence(AnomalyEvent anomaly, SessionAnalysis session) {
        V36RuleEvidenceDto dto = new V36RuleEvidenceDto();
        dto.setRuleRiskScore(firstNonNull(anomaly.getRuleRiskScore(), session == null ? null : session.getRuleRiskScore()));
        dto.setTriggeredRules(parseStringList(firstText(anomaly.getTriggeredRulesJson(), anomaly.getRuleType(), session == null ? null : session.getTriggeredRulesJson())));
        dto.setRuleContributions(parseObjectMap(session == null ? null : session.getRuleContributionsJson()));
        return dto;
    }

    /**
     * Builds anomaly-type attribution DTO by merging anomaly and session data,
     * preferring the anomaly's type, confidence, source, and evidence.
     */
    private V36AnomalyTypeAttributionDto buildAttribution(AnomalyEvent anomaly, SessionAnalysis session) {
        V36AnomalyTypeAttributionDto dto = new V36AnomalyTypeAttributionDto();
        dto.setAnomalyType(anomaly.getAnomalyType());
        dto.setConfidence(firstNonNull(anomaly.getAnomalyTypeConfidence(), anomaly.getTypeConfidence(), session == null ? null : session.getAnomalyTypeConfidence()));
        dto.setSource(firstText(anomaly.getAnomalyTypeSource(), session == null ? null : session.getAnomalyTypeSource()));
        dto.setEvidence(parseObjectMap(firstText(anomaly.getAnomalyTypeEvidenceJson(), session == null ? null : session.getAnomalyTypeEvidenceJson())));
        return dto;
    }

    /**
     * Builds churn-context DTO by merging anomaly and optional session data
     * for churn probability, risk level, model name, model artifact, and
     * feature warnings.
     */
    private V36ChurnContextDto buildChurnContext(AnomalyEvent anomaly, SessionAnalysis session) {
        V36ChurnContextDto dto = new V36ChurnContextDto();
        dto.setProbability(firstNonNull(anomaly.getChurnProbability(), session == null ? null : session.getChurnProbability()));
        dto.setRiskLevel(firstText(anomaly.getChurnRiskLevel(), session == null ? null : session.getChurnRiskLevel()));
        dto.setModelName(session == null ? null : session.getChurnModelName());
        dto.setModelArtifact(session == null ? null : session.getChurnModelArtifact());
        dto.setFeatureWarnings(parseObjectMap(session == null ? null : session.getChurnFeatureWarningsJson()));
        return dto;
    }

    /**
     * Builds forecast-context DTO from a session analysis, or returns
     * {@code null} when no session is available.
     */
    private V36ForecastContextDto buildForecastContext(SessionAnalysis session) {
        if (session == null) {
            return null;
        }
        V36ForecastContextDto dto = new V36ForecastContextDto();
        dto.setRaw(parseObjectMap(session.getForecastContextJson()));
        return dto;
    }

    /**
     * Builds a persona DTO (always disabled in V3.6.1), extracting cluster,
     * label, source, and confidence from session or anomaly data.
     */
    private V36PersonaDisabledDto buildPersona(SessionAnalysis session, AnomalyEvent anomaly) {
        V36PersonaDisabledDto dto = new V36PersonaDisabledDto();
        dto.setEnabled(false);
        dto.setCluster(firstNonNull(session == null ? null : session.getPersonaCluster(), anomaly.getPersonaCluster(), -1));
        dto.setLabel(firstText(session == null ? null : session.getPersonaLabel(), anomaly.getPersonaLabel(), "persona_disabled"));
        dto.setSource(firstText(session == null ? null : session.getPersonaSource(), "disabled_v3_6_refactor"));
        dto.setConfidence(session == null ? null : session.getPersonaConfidence());
        return dto;
    }

    /**
     * Calls {@link V36AlertInvestigationDetailDto#buildSessionLifecycle()} to
     * rebuild the nested sessionLifecycle map from authoritative top-level fields.
     */
    private void populateSessionLifecycle(V36AlertInvestigationDetailDto detail) {
        detail.buildSessionLifecycle();
    }

    /**
     * Enriches session-lifecycle fields on the detail (sessionEndReason,
     * sessionEndedExplicitly, sessionEndedAt, sessionDurationMs,
     * sessionEventCount, sessionLifecycle, runtimeWarnings, modelScores,
     * modelContributions, triggeredRules) from the given payload JSON.
     * Only sets fields that are currently null.
     */
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

    /**
     * Safely parses a JSON string into a {@link V36AlertInvestigationDetailDto},
     * returning empty on parse failure.
     */
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

    /**
     * Normalises an investigation detail by setting defaults for schema version,
     * LLM evidence Redis key, warnings, runtime warnings, triggered rules, LLM
     * section, and source.  Also extracts session lifecycle from nested maps
     * and rebuilds the lifecycle map.
     */
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
            detail.setWarnings(new ArrayList<>());
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

    /**
     * Extracts top-level session-lifecycle fields (sessionEndReason,
     * sessionEndedExplicitly, sessionEndedAt, sessionDurationMs,
     * sessionEventCount) from the nested sessionLifecycle map when they are
     * not already set directly on the detail.
     */
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

    /**
     * Normalises a live alert summary by setting defaults for schema version,
     * recordId, LLM evidence Redis key, triggered rule codes, persona label,
     * and warnings list.
     */
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

    /**
     * Hydrates missing alert fields: derives risk level from final risk score
     * when absent and adds a warning if the risk level is still unavailable.
     */
    private V36LiveAlertSummaryDto hydrateAlertFields(V36LiveAlertSummaryDto alert) {
        if (alert == null) return null;
        hydrateRiskLevelFromScore(alert);
        if (!hasText(alert.getRiskLevel())) {
            alert.getWarnings().add("Risk level unavailable");
        }
        return alert;
    }

    /**
     * Derives a risk-level string from the alert's final risk score when the
     * risk level is not already set.  Uses standard thresholds: CRITICAL >= 80,
     * HIGH >= 60, MEDIUM >= 35, otherwise LOW.
     */
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

    /**
     * Enriches the investigation detail with a next-event prediction for the
     * current session + event context.  Sets nextEventPredictionEvidence with
     * the prediction and optional deviation data, and adds warnings when the
     * prediction is unavailable, mismatched, or the deviation refers to the
     * same context.
     */
    private void enrichNextEventPrediction(V36AlertInvestigationDetailDto detail, AnomalyEvent anomaly) {
        if (detail == null) return;
        String sessionId = detail.getSessionId();
        if (sessionId == null && anomaly != null) {
            sessionId = anomaly.getSessionId();
        }
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            String eventId = detail.getEventId();
            V36NextEventPredictionDto prediction = nextEventPredictionService
                    .getPredictionByContextEventId(sessionId, eventId);
            if (prediction.getHeads() == null || prediction.getHeads().isEmpty()) {
                detail.addWarning("next_event_prediction_evidence_unavailable_for_event");
                return;
            }
            if (!isPredictionMatchingEvent(prediction, eventId)) {
                detail.addWarning("next_event_prediction_context_mismatch");
                return;
            }
            Map<String, Object> evidence = new java.util.LinkedHashMap<>();
            evidence.put("prediction", objectMapper.convertValue(prediction, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            if (prediction.getDeviation() != null) {
                Map<String, Object> deviationMap = objectMapper.convertValue(prediction.getDeviation(), Map.class);
                if (isDeviationActualMismatch(detail.getEventMetadata(), deviationMap)) {
                    detail.addWarning("next_event_prediction_actual_mismatch");
                    return;
                }
                if (isSameContextDeviation(deviationMap)) {
                    detail.addWarning("next_event_prediction_same_context_deviation_ignored");
                } else {
                    evidence.put("deviation", deviationMap);
                }
            }
            detail.setNextEventPredictionEvidence(evidence);
        } catch (Exception e) {
            log.debug("Failed to enrich nextEventPredictionEvidence for eventId={}", detail.getEventId(), e);
        }
    }

    /**
     * Checks whether a prediction was generated for (or evaluated against) the
     * given event.  Returns {@code true} if the contextEventId or the deviation's
     * evaluatedEventId matches.
     */
    private boolean isPredictionMatchingEvent(V36NextEventPredictionDto prediction, String eventId) {
        if (eventId == null) return true;
        if (eventId.equals(prediction.getContextEventId())) return true;
        if (prediction.getDeviation() != null) {
            if (eventId.equals(prediction.getDeviation().getEvaluatedEventId())) return true;
        }
        return false;
    }

    /**
     * Checks whether the deviation's "actual" values differ from the event
     * metadata.  Returns {@code true} when there is a significant difference,
     * meaning the deviation should be kept rather than suppressed.
     */
    @SuppressWarnings("unchecked")
    private boolean isDeviationActualMismatch(Map<String, Object> eventMetadata, Map<String, Object> deviationMap) {
        if (eventMetadata == null || deviationMap == null) return false;
        Object actual = deviationMap.get("actual");
        if (!(actual instanceof Map<?, ?> actualMap)) return false;
        Map<String, Object> typedActual = (Map<String, Object>) actualMap;
        if (matchesMetadata(eventMetadata, typedActual, "apiTemplate", "api_template")) return false;
        if (matchesMetadata(eventMetadata, typedActual, "apiFamily", "api_family")) return false;
        if (matchesMetadata(eventMetadata, typedActual, "status", "status")) return false;
        if (matchesMetadata(eventMetadata, typedActual, "httpMethod", "http_method")) return false;
        return true;
    }

    /**
     * Compares a metadata field with a deviation's "actual" field.
     * Returns {@code true} if both values are present and equal (match).
     */
    private boolean matchesMetadata(Map<String, Object> metadata, Map<String, Object> actual,
                                      String metadataKey, String actualKey) {
        Object meta = metadata.get(metadataKey);
        Object act = actual.get(actualKey);
        if (meta == null || act == null) return true;
        return meta.toString().equals(act.toString());
    }

    /**
     * Detects "same-context" deviations where the evaluated event is the same
     * as the previous prediction's context event.  These deviations contain no
     * new information and should be suppressed.
     */
    private boolean isSameContextDeviation(Map<String, Object> deviationMap) {
        String evaluatedEventId = deviationMap.get("evaluatedEventId") instanceof String s ? s : null;
        if (evaluatedEventId == null) return false;
        String previousContextId = deviationMap.get("previousPredictionContextEventId") instanceof String s ? s : null;
        if (evaluatedEventId.equals(previousContextId)) return true;
        Object previousPrediction = deviationMap.get("previousPrediction");
        if (previousPrediction instanceof Map<?, ?> pp) {
            Object contextId = pp.get("contextEventId");
            return contextId instanceof String && evaluatedEventId.equals(contextId);
        }
        return false;
    }

    /**
     * Enriches a list of alert summaries with data from stored anomaly-event
     * payloads by bulk-fetching anomaly entities for all event IDs and
     * hydrating missing fields.
     *
     * @param alerts the list of alerts to enrich
     * @return the same list with additional fields populated from DB payloads
     */
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

    /**
     * Hydrates a single alert summary from the corresponding anomaly event
     * entity, filling in missing fields from the entity's columns, investigation
     * payload, and LLM evidence payload.
     *
     * @param alert   the alert summary to enrich
     * @param anomaly the anomaly event entity
     * @return a list of field names that were hydrated (for diagnostics)
     */
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

    /**
     * Hydrates a live alert summary from an investigation detail DTO, copying
     * timestamp, event-metadata fields, model scores, model contributions,
     * triggered rule codes, and LLM evidence availability.
     *
     * @return a list of field names that were hydrated
     */
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

    /**
     * Hydrates a live alert summary from an LLM evidence JSON node, extracting
     * event-metadata fields, model scores, model contributions, and marking
     * LLM evidence as available.
     *
     * @return a list of field names that were hydrated
     */
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

    /**
     * Parses a string that may be a JSON array, a JSON object (keys become
     * items), or a comma-separated list into a {@link List} of strings.
     *
     * @param jsonOrValue the raw string value
     * @return a list of parsed string values
     */
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
        /* Treat as comma-separated values. */
        List<String> values = new ArrayList<>();
        for (String value : trimmed.split(",")) {
            String item = value.trim();
            if (!item.isEmpty()) {
                values.add(item);
            }
        }
        return values;
    }

    /**
     * Builds an event-metadata map from the event's JSON payload.  Handles
     * both per-event JSON objects and session-summary objects (identified by
     * the presence of a "lastEventId" field).
     *
     * @param eventJson the raw event JSON string
     * @return a map of metadata key-value pairs, or {@code null}
     */
    private Map<String, Object> buildEventMetadataFromEventJson(String eventJson) {
        JsonNode node = parseJson(eventJson);
        if (node == null || !node.isObject()) {
            return null;
        }
        boolean isSessionSummary = textAt(node, "lastEventId") != null;
        if (isSessionSummary) {
            Map<String, Object> meta = new LinkedHashMap<>();
            putIfPresent(meta, "eventId", textAt(node, "lastEventId"));
            putIfPresent(meta, "eventAction", textAt(node, "lastAction"));
            putIfPresent(meta, "page", textAt(node, "page"));
            putIfPresent(meta, "status", textAt(node, "status"));
            putIfPresent(meta, "timestamp", textAt(node, "lastEventTime"), textAt(node, "timestamp"));
            return meta.isEmpty() ? null : meta;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        putIfPresent(meta, "eventId", textAt(node, "eventId"));
        putIfPresent(meta, "eventAction", textAt(node, "eventAction"), textAt(node, "action"));
        putIfPresent(meta, "apiTemplate", textAt(node, "apiTemplate"), textAt(node, "route"));
        putIfPresent(meta, "apiFamily", textAt(node, "apiFamily"));
        putIfPresent(meta, "controller", textAt(node, "controller"));
        putIfPresent(meta, "page", textAt(node, "page"));
        putIfPresent(meta, "country", textAt(node, "country"), textAt(node, "countryCode"));
        putIfPresent(meta, "device", textAt(node, "device"));
        putIfPresent(meta, "browser", textAt(node, "browser"));
        putIfPresent(meta, "os", textAt(node, "os"));
        putIfPresent(meta, "httpMethod", textAt(node, "httpMethod"), textAt(node, "method"));
        putIfPresent(meta, "status", textAt(node, "status"));
        putIfPresent(meta, "timestamp", textAt(node, "eventTime"), textAt(node, "timestamp"));
        return meta.isEmpty() ? null : meta;
    }

    /**
     * Puts a value into the map only if at least one of the candidate values
     * is non-empty.  Uses the first non-empty value found.
     */
    private void putIfPresent(Map<String, Object> map, String key, String... values) {
        String value = firstText(values);
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * Extracts session-lifecycle fields (sessionEndReason,
     * sessionEndedExplicitly, sessionEndedAt, sessionDurationMs,
     * sessionEventCount, sessionLifecycle) from a raw payload JSON string
     * and sets them on the detail if they are currently null.
     */
    private void extractLifecycleFromPayload(V36AlertInvestigationDetailDto detail, String payloadJson) {
        if (!hasText(payloadJson)) return;
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            if (node == null || !node.isObject()) return;
            if (detail.getSessionEndReason() == null) {
                detail.setSessionEndReason(textAt(node, "sessionEndReason"));
            }
            if (detail.getSessionEndedExplicitly() == null && node.has("sessionEndedExplicitly")) {
                detail.setSessionEndedExplicitly(node.path("sessionEndedExplicitly").asBoolean(false));
            }
            if (detail.getSessionEndedAt() == null && node.has("sessionEndedAt")) {
                String endedAt = node.path("sessionEndedAt").asText(null);
                if (endedAt != null) {
                    try { detail.setSessionEndedAt(Instant.parse(endedAt)); } catch (Exception ignored) {}
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
                    Map<String, Object> lcMap = objectMapper.convertValue(lifecycle, new TypeReference<Map<String, Object>>() {});
                    if (!lcMap.isEmpty()) detail.setSessionLifecycle(lcMap);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.debug("Failed to extract session lifecycle from payload", e);
        }
    }

    /**
     * Checks whether a JSON payload pertains to the requested event by
     * scanning multiple identity fields (eventId, recordId, contextEventId,
     * evaluatedEventId) at the root level, inside eventMetadata, and inside
     * a nested "data" object.  Returns {@code true} only if at least one
     * field matches and none conflict.
     *
     * @param payload           the JSON payload to validate
     * @param requestedEventId  the expected event identifier
     * @param recordId          an alternative record identifier (may be null)
     * @return {@code true} if the payload is for the requested event
     */
    private boolean isPayloadForRequestedEvent(JsonNode payload, String requestedEventId, String recordId) {
        if (payload == null || !payload.isObject() || requestedEventId == null) return false;
        Set<String> allowedIds = new HashSet<>();
        allowedIds.add(requestedEventId);
        if (recordId != null) allowedIds.add(recordId);
        Set<String> matchingIds = new HashSet<>();
        Set<String> conflictingIds = new HashSet<>();
        addCandidate(payload, matchingIds, conflictingIds, allowedIds, "eventId");
        addCandidate(payload, matchingIds, conflictingIds, allowedIds, "recordId");
        addCandidate(payload, matchingIds, conflictingIds, allowedIds, "contextEventId");
        addCandidate(payload, matchingIds, conflictingIds, allowedIds, "evaluatedEventId");
        JsonNode metadata = payload.path("eventMetadata");
        if (metadata.isObject()) {
            addCandidate(metadata, matchingIds, conflictingIds, allowedIds, "eventId");
            addCandidate(metadata, matchingIds, conflictingIds, allowedIds, "recordId");
        }
        JsonNode data = payload.path("data");
        if (data.isObject()) {
            addCandidate(data, matchingIds, conflictingIds, allowedIds, "eventId");
            addCandidate(data, matchingIds, conflictingIds, allowedIds, "recordId");
            addCandidate(data, matchingIds, conflictingIds, allowedIds, "contextEventId");
            addCandidate(data, matchingIds, conflictingIds, allowedIds, "evaluatedEventId");
            JsonNode dataMetadata = data.path("eventMetadata");
            if (dataMetadata.isObject()) {
                addCandidate(dataMetadata, matchingIds, conflictingIds, allowedIds, "eventId");
                addCandidate(dataMetadata, matchingIds, conflictingIds, allowedIds, "recordId");
            }
        }
        if (!conflictingIds.isEmpty()) return false;
        if (matchingIds.isEmpty()) return false;
        return true;
    }

    /**
     * Inspects a field in a JSON node and classifies its value as either
     * matching or conflicting with the set of allowed IDs.
     */
    private void addCandidate(JsonNode parent, Set<String> matchingIds, Set<String> conflictingIds, Set<String> allowedIds, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || !node.isTextual()) return;
        String value = node.asText();
        if (allowedIds.contains(value)) {
            matchingIds.add(value);
        } else {
            conflictingIds.add(value);
        }
    }

    /**
     * Validates that a raw payload map is for the requested event by checking
     * the top-level eventId and the nested eventMetadata.eventId fields.
     */
    private boolean isRawPayloadForEvent(Map<String, Object> rawPayload, String requestedEventId) {
        if (rawPayload == null || requestedEventId == null) return false;
        String eventId = mapText(rawPayload, "eventId");
        if (eventId != null && !requestedEventId.equals(eventId)) return false;
        Object rawMetadata = rawPayload.get("eventMetadata");
        if (rawMetadata instanceof Map) {
            Map<String, Object> metaMap = (Map<String, Object>) rawMetadata;
            String metaEventId = mapText(metaMap, "eventId");
            if (metaEventId != null && !requestedEventId.equals(metaEventId)) return false;
        }
        return true;
    }

    /** Extracts a String value from a map by key, returning {@code null} if absent or not a String. */
    private String mapText(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s ? s : null;
    }

    /**
     * Checks whether a {@link V36AlertInvestigationDetailDto} corresponds to
     * the requested event by comparing eventId and recordId, and also validating
     * the eventMetadata.eventId field.
     */
    private boolean isDetailForRequestedEvent(V36AlertInvestigationDetailDto detail, String requestedEventId) {
        if (detail == null || requestedEventId == null) return false;
        if (!requestedEventId.equals(detail.getEventId()) && !requestedEventId.equals(detail.getRecordId())) {
            return false;
        }
        if (detail.getEventMetadata() != null) {
            Object metaEventId = detail.getEventMetadata().get("eventId");
            if (metaEventId instanceof String metaId) {
                String recordId = detail.getRecordId();
                if (!requestedEventId.equals(metaId) && (recordId == null || !recordId.equals(metaId))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Enriches the investigation detail with session-lifecycle data from
     * multiple sources in priority order:
     * <ol>
     *   <li>Session analysis entity columns (duration, event count)</li>
     *   <li>Session analysis investigation payload</li>
     *   <li>Anomaly event investigation payload</li>
     *   <li>Existing detail values (Redis / stale data, lowest priority)</li>
     * </ol>
     * Rebuilds the nested sessionLifecycle map and adds a warning if no
     * lifecycle data is found.
     */
    private void enrichSessionLifecycle(V36AlertInvestigationDetailDto detail, AnomalyEvent anomaly, SessionAnalysis session) {
        if (detail == null) return;

        String sessionId = detail.getSessionId();
        String insuredId = detail.getInsuredId();
        if (!hasText(sessionId) && anomaly != null) sessionId = anomaly.getSessionId();
        if (!hasText(insuredId) && anomaly != null) insuredId = anomaly.getInsuredId();
        if (!hasText(sessionId)) return;

        // Save existing values as lowest-priority fallback (Redis stale data)
        String existingEndReason = detail.getSessionEndReason();
        Boolean existingEndedExplicitly = detail.getSessionEndedExplicitly();
        Instant existingEndedAt = detail.getSessionEndedAt();
        Long existingDurationMs = detail.getSessionDurationMs();
        Integer existingEventCount = detail.getSessionEventCount();
        Map<String, Object> existingLifecycle = detail.getSessionLifecycle();

        // Clear lifecycle to start fresh from authoritative sources
        detail.setSessionEndReason(null);
        detail.setSessionEndedExplicitly(null);
        detail.setSessionEndedAt(null);
        detail.setSessionDurationMs(null);
        detail.setSessionEventCount(null);
        detail.setSessionLifecycle(null);

        // Priority 1 & 2: session_analysis (session-scoped, authoritative for lifecycle)
        SessionAnalysis sessionForLifecycle = null;
        if (session != null && sessionId.equals(session.getSessionId())) {
            sessionForLifecycle = session;
        } else if (hasText(insuredId)) {
            try {
                sessionForLifecycle = sessionAnalysisRepository
                        .findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(insuredId, sessionId)
                        .orElse(null);
            } catch (Exception e) {
                log.debug("Failed to enrich session lifecycle from analysis for sessionId={}", sessionId, e);
            }
        }

        if (sessionForLifecycle != null) {
            // Priority 1: entity columns
            if (sessionForLifecycle.getSessionDurationSeconds() != null) {
                detail.setSessionDurationMs(sessionForLifecycle.getSessionDurationSeconds() * 1000L);
            }
            if (sessionForLifecycle.getTotalEvents() != null) {
                detail.setSessionEventCount(sessionForLifecycle.getTotalEvents());
            }
            // Priority 2: payload lifecycle fields (for fields not in entity columns)
            extractLifecycleFromPayload(detail, sessionForLifecycle.getInvestigationPayloadJson());
        }

        // Priority 3: exact event payload lifecycle fields
        if (detail.getSessionEndReason() == null) {
            extractLifecycleFromPayload(detail, anomaly == null ? null : anomaly.getInvestigationPayloadJson());
        }

        // Priority 4: existing detail values (Redis/stale, lowest priority)
        if (detail.getSessionEndReason() == null) detail.setSessionEndReason(existingEndReason);
        if (detail.getSessionEndedExplicitly() == null) detail.setSessionEndedExplicitly(existingEndedExplicitly);
        if (detail.getSessionEndedAt() == null) detail.setSessionEndedAt(existingEndedAt);
        if (detail.getSessionDurationMs() == null) detail.setSessionDurationMs(existingDurationMs);
        if (detail.getSessionEventCount() == null) detail.setSessionEventCount(existingEventCount);
        if (detail.getSessionLifecycle() == null) detail.setSessionLifecycle(existingLifecycle);

        // Rebuild nested sessionLifecycle from authoritative top-level fields
        detail.buildSessionLifecycle();

        // Warning if nothing found
        if (detail.getSessionEndReason() == null && detail.getSessionDurationMs() == null) {
            detail.addWarning("session_lifecycle_unavailable");
        }
    }

    /**
     * Enriches the investigation detail with a timestamp from one of several
     * sources in priority order: Redis live alert payload, eventMetadata.eventTime,
     * or the SQL anomaly event's timestamp.
     */
    private void enrichTimestamp(V36AlertInvestigationDetailDto detail, AnomalyEvent anomaly, String eventId) {
        if (detail == null || detail.getTimestamp() != null || eventId == null) return;

        // 1. Redis live alert payload timestamp
        try {
            V36LiveAlertSummaryDto live = redisReadService.readValue(
                    CacheKeys.liveAlertPayloadKey(eventId), V36LiveAlertSummaryDto.class).orElse(null);
            if (live != null) {
                if (live.getTimestamp() != null) {
                    detail.setTimestamp(live.getTimestamp());
                    return;
                }
                if (live.getCreatedAt() != null) {
                    detail.setTimestamp(live.getCreatedAt());
                    return;
                }
            }
        } catch (Exception ignored) {}

        // 2. eventMetadata.eventTime
        if (detail.getEventMetadata() != null) {
            Object eventTime = detail.getEventMetadata().get("eventTime");
            if (eventTime instanceof String) {
                try {
                    detail.setTimestamp(Instant.parse((String) eventTime));
                    return;
                } catch (Exception ignored) {}
            }
        }

        // 3. SQL anomaly timestamp (lowest priority)
        if (anomaly != null) {
            detail.setTimestamp(firstNonNull(anomaly.getEventTime(), anomaly.getDetectedAt()));
        }
    }

    /**
     * Enriches a SQL-sourced investigation detail with metadata from the Redis
     * live alert payload when available and matching the requested event.
     * Sets the source to "sql+redis-payload" and adds appropriate warnings.
     */
    private void enrichFromLivePayloadIfNeeded(V36AlertInvestigationDetailDto detail, String eventId, AnomalyEvent anomaly) {
        if (detail == null || !"sql".equals(detail.getSource())) return;

        Optional<V36LiveAlertSummaryDto> livePayload = redisReadService.readValue(
                CacheKeys.liveAlertPayloadKey(eventId), V36LiveAlertSummaryDto.class);
        if (livePayload.isEmpty()) return;

        V36LiveAlertSummaryDto live = livePayload.get();
        if (!eventId.equals(live.getEventId()) && !eventId.equals(live.getRecordId())) {
            detail.addWarning("redis_event_payload_mismatch");
            return;
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        putIfPresent(meta, "eventId", live.getEventId());
        putIfPresent(meta, "eventAction", live.getEventAction());
        putIfPresent(meta, "apiTemplate", live.getApiTemplate());
        putIfPresent(meta, "apiFamily", live.getApiFamily());
        putIfPresent(meta, "controller", live.getController());
        putIfPresent(meta, "page", live.getPage());
        putIfPresent(meta, "country", live.getCountry());
        putIfPresent(meta, "device", live.getDevice());
        putIfPresent(meta, "browser", live.getBrowser());
        putIfPresent(meta, "os", live.getOs());
        putIfPresent(meta, "httpMethod", live.getHttpMethod());
        putIfPresent(meta, "status", live.getStatus());
        if (!meta.isEmpty()) {
            detail.setEventMetadata(meta);
        }

        if (detail.getTimestamp() == null) {
            detail.setTimestamp(firstNonNull(live.getTimestamp(), live.getCreatedAt()));
        }

        detail.setSource("sql+redis-payload");
        if (detail.getWarnings() != null) {
            detail.getWarnings().remove("exact_event_payload_unavailable");
        }
        detail.addWarning("redis_event_payload_used");
    }

    /**
     * Applies event-scoping guards to prevent cross-event data leakage.
     * Verifies raw-payload eventId, clears session-derived evidence when no
     * exact payload is available, checks anomaly-type attribution score
     * consistency, and cleans leaked fields from eventMetadata.
     */
    private void guardEventScoping(V36AlertInvestigationDetailDto detail, AnomalyEvent anomaly, SessionAnalysis session) {
        if (detail == null) return;

        String requestedEventId = anomaly != null ? anomaly.getEventId() : null;
        boolean hasExactPayload = false;

        // Raw payload eventId verification
        if (detail.getRawPayload() != null && requestedEventId != null) {
            Object rawEventId = detail.getRawPayload().get("eventId");
            if (requestedEventId.equals(rawEventId)) {
                hasExactPayload = true;
                // Also verify eventMetadata.eventId in raw payload
                if (!isRawPayloadForEvent(detail.getRawPayload(), requestedEventId)) {
                    hasExactPayload = false;
                    detail.setRawPayload(null);
                    detail.addWarning("sql_payload_event_mismatch");
                }
            } else {
                detail.setRawPayload(null);
                detail.addWarning("raw_payload_event_mismatch");
            }
        }

        // Safe mode: no exact payload -> clear session-derived evidence
        if (!hasExactPayload) {
            detail.setSequenceEvidence(null);
            detail.setTabularEvidence(null);
            detail.setForecastContext(null);
            if (detail.getRuleEvidence() != null) {
                detail.getRuleEvidence().setRuleContributions(null);
            }
            if (detail.getChurnContext() != null) {
                detail.getChurnContext().setFeatureWarnings(null);
                detail.getChurnContext().setModelName(null);
                detail.getChurnContext().setModelArtifact(null);
            }
            if (detail.getAnomalyTypeAttribution() != null && detail.getAnomalyTypeAttribution().getEvidence() != null) {
                detail.addWarning("anomaly_type_attribution_event_mismatch");
                detail.getAnomalyTypeAttribution().setEvidence(null);
            }
        } else {
            // Score mismatch check when exact payload is available
            if (detail.getAnomalyTypeAttribution() != null && detail.getAnomalyTypeAttribution().getEvidence() != null) {
                Map<String, Object> evidence = detail.getAnomalyTypeAttribution().getEvidence();
                Object scoreInEvidence = evidence.get("finalRiskScore");
                if (scoreInEvidence instanceof Number && detail.getFinalRiskScore() != null
                        && Math.abs(((Number) scoreInEvidence).doubleValue() - detail.getFinalRiskScore()) > 0.001) {
                    detail.getAnomalyTypeAttribution().setEvidence(null);
                    detail.addWarning("anomaly_type_attribution_event_mismatch");
                }
            }
        }

        // Clean eventMetadata of top-level fields that leaked in
        if (detail.getEventMetadata() != null) {
            detail.getEventMetadata().remove("triggeredRules");
            detail.getEventMetadata().remove("finalRiskScore");
        }
    }

    /**
     * Parses a JSON string into a {@code Map<String, Object>}.
     * Returns an empty map on parse failure or when the JSON is not an object.
     */
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

    /**
     * Parses a JSON string into a {@code List<Map<String, Object>>}.
     * Returns an empty list on parse failure.
     */
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

    /** Safely parses a JSON string into a {@link JsonNode}, returning {@code null} on failure. */
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

    /** Returns the text value of a JSON field, or {@code null} if absent or not a value node. */
    private String textAt(JsonNode node, String fieldName) {
        if (node == null || fieldName == null) {
            return null;
        }
        String value = node.path(fieldName).asText(null);
        return hasText(value) ? value : null;
    }

    /**
     * Returns the first numeric value found in the map for any of the given
     * candidate names, converted to {@link Double}.
     */
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

    /** Returns the first non-null value from a varargs array. */
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

    /** Returns the first non-blank string from a varargs array, or {@code null} if all blank. */
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

    /**
     * Case-insensitive equality check for filter matching.  Returns {@code true}
     * when the expected value is blank (no filter) or when actual equals expected.
     */
    private boolean matches(String expected, String actual) {
        return !hasText(expected) || (hasText(actual) && expected.equalsIgnoreCase(actual));
    }

    /**
     * Builds the LLM explanation section for an investigation detail, containing
     * evidence availability, Redis key, and REST endpoint URLs.
     */
    private Map<String, Object> buildLlmSection(String eventId, Boolean evidenceAvailable, String evidenceRedisKey) {
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("evidenceAvailable", Boolean.TRUE.equals(evidenceAvailable));
        llm.put("evidenceRedisKey", evidenceRedisKey);
        llm.put("evidenceEndpoint", "/api/v1/explanations/alerts/" + eventId + "/evidence");
        llm.put("cachedExplanationEndpoint", "/api/v1/explanations/alerts/" + eventId);
        llm.put("generateExplanationEndpoint", "POST /api/v1/explanations/alerts/" + eventId);
        return llm;
    }

    /** Converts a blank string to {@code null} (for optional query parameters). */
    private String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    /**
     * Normalises a user-provided limit to the range [1, 500], defaulting to
     * 100 when the input is less than 1.
     */
    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 100;
        }
        return Math.min(limit, 500);
    }

    /** Returns {@code true} if the string is non-null and contains non-whitespace characters. */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
