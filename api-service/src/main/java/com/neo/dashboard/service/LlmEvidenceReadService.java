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
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmEvidenceReadService {

    private static final Duration REHYDRATE_TTL = Duration.ofHours(24);

    private final V36RedisReadService redisReadService;
    private final AnomalyEventRepository anomalyEventRepository;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.v36.explanations.evidence-rehydrate-ttl-hours:24}")
    private long evidenceRehydrateTtlHours;

    public Optional<JsonNode> readEvidence(String eventId) {
        if (!hasText(eventId)) {
            return Optional.empty();
        }
        Optional<JsonNode> redis = redisReadService.readJson(CacheKeys.alertLlmEvidenceKey(eventId));
        if (redis.isPresent()) {
            return redis;
        }
        Optional<JsonNode> sql = readEvidenceFromSql(eventId);
        if (sql.isPresent()) {
            rehydrateRedis(eventId, sql.get());
        }
        return sql;
    }

    public JsonNode requireEvidence(String eventId) {
        return readEvidence(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND", "LLM evidence payload not found"));
    }

    public Object requireEvidenceAsObject(String eventId) {
        JsonNode node = requireEvidence(eventId);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SERIALIZATION_ERROR", "Failed to serialize evidence payload");
        }
    }

    public boolean isV36Evidence(JsonNode evidence) {
        return evidence != null && CacheKeys.V36_SCHEMA_VERSION.equals(evidence.path("schemaVersion").asText(null));
    }

    public String evidenceHash(JsonNode evidence) {
        if (evidence == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE", "Evidence payload is null");
        }
        String existing = textAt(evidence, "/evidenceHash");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        try {
            String canonical = objectMapper.writeValueAsString(evidence);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE", "Unable to hash LLM evidence payload");
        }
    }

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

    @Transactional(readOnly = true)
    Optional<JsonNode> readEvidenceFromSql(String eventId) {
        return anomalyEventRepository.findTopByEventIdOrderByDetectedAtDesc(eventId)
                .flatMap(anomaly -> {
                    Optional<JsonNode> eventPayload = parsePayload(anomaly.getLlmExplanationEvidencePayloadJson());
                    if (eventPayload.isPresent()) {
                        return eventPayload;
                    }
                    return sessionAnalysisRepository
                            .findTopByInsuredIdAndSessionIdOrderByCreatedAtDesc(anomaly.getInsuredId(), anomaly.getSessionId())
                            .flatMap(session -> parsePayload(session.getLlmExplanationEvidencePayloadJson()));
                });
    }

    private void rehydrateRedis(String eventId, JsonNode evidence) {
        try {
            Duration ttl = Duration.ofHours(Math.max(evidenceRehydrateTtlHours, 1));
            redisReadService.writeJson(CacheKeys.alertLlmEvidenceKey(eventId), evidence, ttl);
            log.info("Rehydrated Redis key={} from SQL evidence fallback", CacheKeys.alertLlmEvidenceKey(eventId));
        } catch (Exception e) {
            log.warn("Failed to rehydrate Redis evidence for eventId={}", eventId, e);
        }
    }

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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
