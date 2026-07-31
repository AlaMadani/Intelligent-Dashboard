package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * Reads LLM-generated evidence payloads for a given event. Uses a Redis-first
 * strategy and falls back to SQL (via {@link AnomalyEventRepository} and
 * {@link SessionAnalysisRepository}) when the cache misses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmEvidenceReadService {

    /** Default TTL when re-hydrating evidence from SQL into Redis. */
    private static final Duration REHYDRATE_TTL = Duration.ofHours(24);

    /** Redis read helper for fetching/deserialising cached evidence. */
    private final V36RedisReadService redisReadService;
    /** Primary source of evidence payloads in the anomaly event table. */
    private final AnomalyEventRepository anomalyEventRepository;
    /** Secondary source of evidence payloads in the session analysis table. */
    private final SessionAnalysisRepository sessionAnalysisRepository;
    /** JSON tree reader. */
    private final ObjectMapper objectMapper;

    /** Configurable TTL (in hours) for evidence re-hydrated into Redis. */
    @Value("${app.v36.explanations.evidence-rehydrate-ttl-hours:24}")
    private long evidenceRehydrateTtlHours;

    /**
     * Attempts to load evidence for the given event, first from Redis, then
     * from SQL. Results from SQL are re-hydrated into Redis for subsequent
     * fast access. Each candidate is validated to ensure it actually belongs
     * to the requested event.
     *
     * @param eventId the anomaly event identifier
     * @return an Optional containing the evidence {@link JsonNode}, or empty
     */
    public Optional<JsonNode> readEvidence(String eventId) {
        // Reject empty identifiers immediately
        if (!hasText(eventId)) {
            return Optional.empty();
        }
        // Try Redis first
        Optional<JsonNode> redis = redisReadService.readJson(CacheKeys.alertLlmEvidenceKey(eventId));
        if (redis.isPresent()) {
            if (isEvidenceForRequestedEvent(redis.get(), eventId)) {
                return redis;
            }
            log.warn("LLM evidence event mismatch for eventId={}: Redis evidence belongs to a different event", eventId);
        }
        // Fall through to SQL
        Optional<JsonNode> sql = readEvidenceFromSql(eventId);
        if (sql.isPresent()) {
            if (isEvidenceForRequestedEvent(sql.get(), eventId)) {
                rehydrateRedis(eventId, sql.get());
                return sql;
            }
            log.warn("LLM evidence event mismatch for eventId={}: SQL evidence belongs to a different event", eventId);
        }
        return Optional.empty();
    }

    /**
     * Like {@link #readEvidence(String)} but throws
     * {@link ApiException#NOT_FOUND} when no evidence is available.
     *
     * @param eventId the anomaly event identifier
     * @return the evidence as a {@link JsonNode}
     */
    public JsonNode requireEvidence(String eventId) {
        return readEvidence(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND",
                        "LLM evidence payload not found"));
    }

    /**
     * Reads evidence and converts it to a plain {@link Object} for use in
     * generic API responses.
     *
     * @param eventId the anomaly event identifier
     * @return the deserialised evidence, or {@code null} if the node is null
     */
    public Object requireEvidenceAsObject(String eventId) {
        JsonNode node = requireEvidence(eventId);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SERIALIZATION_ERROR",
                    "Failed to serialize evidence payload");
        }
    }

    /**
     * Checks whether the evidence conforms to the V3.6 schema by comparing
     * its {@code schemaVersion} field.
     */
    public boolean isV36Evidence(JsonNode evidence) {
        return evidence != null && CacheKeys.V36_SCHEMA_VERSION.equals(evidence.path("schemaVersion").asText(null));
    }

    /**
     * Returns a stable 16-character hex hash for the evidence. If the
     * evidence already carries an {@code /evidenceHash} field, that value is
     * returned directly; otherwise a SHA-256 digest of the canonical JSON is
     * computed.
     *
     * @param evidence the evidence JSON
     * @return a 16-character hex string
     */
    public String evidenceHash(JsonNode evidence) {
        if (evidence == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE", "Evidence payload is null");
        }
        // Reuse a pre-computed hash if present
        String existing = textAt(evidence, "/evidenceHash");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        // Otherwise compute a SHA-256 digest (truncated to 16 hex chars) of the canonical JSON
        try {
            String canonical = objectMapper.writeValueAsString(evidence);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE", "Unable to hash LLM evidence payload");
        }
    }

    /**
     * Safely extracts a text value from a JSON node via a JSON Pointer
     * expression.
     */
    private String textAt(JsonNode node, String pointer) {
        if (node == null || pointer == null) {
            return null;
        }
        JsonNode value = node.at(pointer);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    /**
     * Reads evidence from the SQL fallback sources. It first checks the
     * anomaly event's evidence payload; if that is missing, it looks up the
     * associated session analysis.
     */
    @Transactional(readOnly = true)
    Optional<JsonNode> readEvidenceFromSql(String eventId) {
        return anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc(eventId)
                .flatMap(anomaly -> {
                    // Try the anomaly event's own evidence first
                    Optional<JsonNode> eventPayload = parsePayload(anomaly.getLlmExplanationEvidencePayloadJson());
                    if (eventPayload.isPresent()) {
                        return eventPayload;
                    }
                    // Fall back to the session analysis evidence for the same insured + session
                    return sessionAnalysisRepository
                            .findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(
                                    anomaly.getInsuredId(), anomaly.getSessionId())
                            .flatMap(session -> parsePayload(session.getLlmExplanationEvidencePayloadJson()));
                });
    }

    /**
     * Writes evidence into Redis so subsequent reads hit the cache.
     * Failures are logged but never propagated.
     */
    private void rehydrateRedis(String eventId, JsonNode evidence) {
        try {
            Duration ttl = Duration.ofHours(Math.max(evidenceRehydrateTtlHours, 1));
            redisReadService.writeJson(CacheKeys.alertLlmEvidenceKey(eventId), evidence, ttl);
            log.info("Rehydrated Redis key={} from SQL evidence fallback", CacheKeys.alertLlmEvidenceKey(eventId));
        } catch (Exception e) {
            log.warn("Failed to rehydrate Redis evidence for eventId={}", eventId, e);
        }
    }

    /** Safely parses a JSON string into a tree node. */
    private Optional<JsonNode> parsePayload(String json) {
        if (!hasText(json)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(json));
        } catch (Exception e) {
            log.warn("Malformed SQL LLM evidence payload", e);
            return Optional.empty();
        }
    }

    /**
     * Validates that a JSON evidence object actually belongs to the requested
     * event by checking {@code /eventId}, {@code /recordId}, and their
     * counterparts inside {@code /eventMetadata}. Returns true only when at
     * least one matching ID is found and no conflicting IDs are present.
     */
    private boolean isEvidenceForRequestedEvent(JsonNode evidence, String eventId) {
        if (evidence == null || !evidence.isObject() || eventId == null) return false;
        Set<String> allowedIds = new HashSet<>();
        allowedIds.add(eventId);
        Set<String> matchingIds = new HashSet<>();
        Set<String> conflictingIds = new HashSet<>();
        // Check top-level identifiers
        checkCandidate(matchingIds, conflictingIds, allowedIds, textAt(evidence, "/eventId"));
        checkCandidate(matchingIds, conflictingIds, allowedIds, textAt(evidence, "/recordId"));
        // Check identifiers nested inside eventMetadata
        JsonNode metadata = evidence.path("eventMetadata");
        if (metadata.isObject()) {
            checkCandidate(matchingIds, conflictingIds, allowedIds, textAt(metadata, "/eventId"));
            checkCandidate(matchingIds, conflictingIds, allowedIds, textAt(metadata, "/recordId"));
        }
        if (!conflictingIds.isEmpty()) return false;
        if (matchingIds.isEmpty()) return false;
        return true;
    }

    /**
     * Helper for {@link #isEvidenceForRequestedEvent}: adds a candidate
     * value to either the matching or conflicting set.
     */
    private void checkCandidate(Set<String> matchingIds, Set<String> conflictingIds,
                                 Set<String> allowedIds, String value) {
        if (value == null) return;
        if (allowedIds.contains(value)) {
            matchingIds.add(value);
        } else {
            conflictingIds.add(value);
        }
    }

    /** Returns {@code true} if the string is non-null and non-blank. */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
