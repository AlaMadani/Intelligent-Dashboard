package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.exception.ApiException;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.AnomalyEventRepository;
import com.neo.dashboard.repository.SessionAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmEvidenceReadService {

    private final V36RedisReadService redisReadService;
    private final AnomalyEventRepository anomalyEventRepository;
    private final SessionAnalysisRepository sessionAnalysisRepository;
    private final ObjectMapper objectMapper;

    public Optional<JsonNode> readEvidence(String eventId) {
        if (!hasText(eventId)) {
            return Optional.empty();
        }
        Optional<JsonNode> redis = redisReadService.readJson(CacheKeys.alertLlmEvidenceKey(eventId));
        if (redis.isPresent()) {
            return redis;
        }
        return readEvidenceFromSql(eventId);
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
        try {
            String canonical = objectMapper.writeValueAsString(evidence);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE", "Unable to hash LLM evidence payload");
        }
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
