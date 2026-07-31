package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36LlmExplanationResponse;
import com.neo.dashboard.entity.LlmExplanation;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.LlmExplanationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Two-tier cache (Redis + SQL) for LLM-generated explanations. Supports
 * fine-grained lookup by event + evidence-hash + style + language, as well
 * as a "latest" shortcut. Includes a distributed lock to prevent concurrent
 * generation of the same explanation on different nodes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmExplanationCacheService {

    /** Redis read helper for deserialising cached responses. */
    private final V36RedisReadService redisReadService;
    /** Low-level Redis client for lock operations. */
    private final StringRedisTemplate redisTemplate;
    /** SQL repository for persistent explanation storage. */
    private final LlmExplanationRepository explanationRepository;
    /** JSON serialisation/deserialisation. */
    private final ObjectMapper objectMapper;

    /** Master switch; when false, all reads bypass the cache. */
    @Value("${app.v36.explanations.cache-enabled:true}")
    private boolean cacheEnabled;

    /** TTL applied to both Redis and re-hydrated SQL explanations. */
    @Value("${app.v36.explanations.cache-ttl-hours:24}")
    private long cacheTtlHours;

    /**
     * Retrieves the latest cached explanation for an event, regardless of
     * style/language. Tries Redis first, then SQL.
     *
     * @param eventId the anomaly event identifier
     * @return the cached response, or empty
     */
    public Optional<V36LlmExplanationResponse> getLatest(String eventId) {
        if (!cacheEnabled || eventId == null) {
            return Optional.empty();
        }
        // Attempt a Redis read and tag the response as cached
        Optional<V36LlmExplanationResponse> redis = redisReadService.readValue(
                CacheKeys.explanationV36LatestKey(eventId), V36LlmExplanationResponse.class)
                .map(response -> {
                    response.setCached(true);
                    response.setSource("redis");
                    return response;
                });
        if (redis.isPresent()) {
            log.info("LLM_EXPLANATION_CACHE_HIT source=redis eventId={}", eventId);
            return redis;
        }
        // Fall back to the most recent SQL row marked as current
        Optional<V36LlmExplanationResponse> sql = readFromSqlLatest(eventId);
        if (sql.isPresent()) {
            V36LlmExplanationResponse response = sql.get();
            response.setCached(true);
            response.setSource("sql_fallback");
            rehydrateRedis(response);
            log.info("LLM_EXPLANATION_CACHE_HIT source=sql_fallback eventId={}", eventId);
            return Optional.of(response);
        }
        return Optional.empty();
    }

    /**
     * Retrieves a cached explanation matching event + evidence hash + style +
     * language. Tries Redis first, then SQL.
     *
     * @param eventId      the anomaly event identifier
     * @param evidenceHash the evidence content hash
     * @param style        the narrative style (e.g. security_analyst)
     * @param language     the language code (e.g. en, fr)
     * @return the cached response, or empty
     */
    public Optional<V36LlmExplanationResponse> get(String eventId, String evidenceHash,
                                                     String style, String language) {
        if (!cacheEnabled || eventId == null) {
            return Optional.empty();
        }
        // Look up the exact key in Redis
        Optional<V36LlmExplanationResponse> redis = redisReadService.readValue(
                CacheKeys.explanationV36Key(eventId, evidenceHash, style, language),
                V36LlmExplanationResponse.class)
                .map(response -> {
                    response.setCached(true);
                    response.setSource("redis");
                    return response;
                });
        if (redis.isPresent()) {
            log.info("LLM_EXPLANATION_CACHE_HIT source=redis eventId={}", eventId);
            return redis;
        }
        // Fall back to SQL
        Optional<V36LlmExplanationResponse> sql = readFromSql(eventId, evidenceHash, style, language);
        if (sql.isPresent()) {
            V36LlmExplanationResponse response = sql.get();
            response.setCached(true);
            response.setSource("sql_fallback");
            rehydrateRedis(response);
            log.info("LLM_EXPLANATION_CACHE_HIT source=sql_fallback eventId={}", eventId);
            return Optional.of(response);
        }
        return Optional.empty();
    }

    /**
     * Persists a freshly generated explanation to both Redis (with TTL) and
     * SQL. Previous explanations for the same event are marked as not current.
     *
     * @param response the explanation to store
     */
    @Transactional
    public void put(V36LlmExplanationResponse response) {
        if (!cacheEnabled || response == null || response.getEventId() == null) {
            return;
        }
        Duration ttl = Duration.ofHours(Math.max(cacheTtlHours, 1));
        // Write the fine-grained key and the "latest" shortcut
        redisReadService.writeJson(
                CacheKeys.explanationV36Key(response.getEventId(), response.getEvidenceHash(),
                        response.getStyle(), response.getLanguage()),
                response, ttl);
        redisReadService.writeJson(CacheKeys.explanationV36LatestKey(response.getEventId()), response, ttl);

        // Persist to the durable SQL store
        saveToSql(response);

        log.info("LLM_EXPLANATION_STORED eventId={} redis=true sql=true", response.getEventId());
    }

    /**
     * Attempts to acquire a distributed Redis lock for a specific event +
     * language + style combination.
     *
     * @param eventId  the anomaly event identifier
     * @param language the language code
     * @param style    the narrative style
     * @param ttl      the lock TTL
     * @return true if the lock was acquired, false if already held
     */
    public boolean tryAcquireLock(String eventId, String language, String style, Duration ttl) {
        String key = CacheKeys.explanationLockKey(eventId, language, style);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "locked", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Releases a previously acquired distributed lock.
     */
    public void releaseLock(String eventId, String language, String style) {
        String key = CacheKeys.explanationLockKey(eventId, language, style);
        redisTemplate.delete(key);
    }

    /** Re-populates Redis from a SQL-sourced response. */
    private void rehydrateRedis(V36LlmExplanationResponse response) {
        if (response == null || response.getEventId() == null) {
            return;
        }
        Duration ttl = Duration.ofHours(Math.max(cacheTtlHours, 1));
        redisReadService.writeJson(
                CacheKeys.explanationV36Key(response.getEventId(), response.getEvidenceHash(),
                        response.getStyle(), response.getLanguage()),
                response, ttl);
        redisReadService.writeJson(CacheKeys.explanationV36LatestKey(response.getEventId()), response, ttl);
    }

    /** Looks up a specific explanation by full key in SQL. */
    private Optional<V36LlmExplanationResponse> readFromSql(String eventId, String evidenceHash,
                                                              String style, String language) {
        return explanationRepository
                .findTopByEventIdAndLanguageAndStyleAndIncludeRecommendedActionsAndEvidenceHashAndCurrentTrue(
                        eventId, language, style, true, evidenceHash)
                .map(this::toResponse);
    }

    /** Looks up the most recent current explanation for an event in SQL. */
    private Optional<V36LlmExplanationResponse> readFromSqlLatest(String eventId) {
        return explanationRepository
                .findTopByEventIdAndCurrentTrueOrderByUpdatedAtDesc(eventId)
                .map(this::toResponse);
    }

    /**
     * Converts a {@link LlmExplanation} JPA entity into an API response DTO,
     * deserialising the stored JSON and overlaying entity-level fields.
     */
    private V36LlmExplanationResponse toResponse(LlmExplanation entity) {
        V36LlmExplanationResponse response;
        try {
            response = objectMapper.readValue(entity.getExplanationJson(), V36LlmExplanationResponse.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize SQL llm_explanation explanation_json for eventId={}",
                    entity.getEventId(), e);
            // Return a minimal fallback with only the available fields
            response = new V36LlmExplanationResponse();
            response.setEventId(entity.getEventId());
            response.setSummary(entity.getSummary());
        }
        // Ensure the schema version is always set
        if (response.getSchemaVersion() == null) {
            response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        }
        response.setEvidenceHash(entity.getEvidenceHash());
        response.setStyle(entity.getStyle());
        response.setLanguage(entity.getLanguage());
        return response;
    }

    /**
     * Persists the explanation to the SQL database. Marks any previous
     * explanation for the same event as not current before inserting a new
     * row.
     */
    private void saveToSql(V36LlmExplanationResponse response) {
        try {
            // Soft-invalidate any previous current row
            explanationRepository.markPreviousAsNotCurrent(
                    response.getEventId(),
                    safe(response.getLanguage(), "en"),
                    safe(response.getStyle(), "security_analyst"),
                    response.getRecommendedActions() != null && !response.getRecommendedActions().isEmpty());

            // Build and persist a new entity
            LlmExplanation entity = new LlmExplanation();
            entity.setSchemaVersion(response.getSchemaVersion());
            entity.setEventId(response.getEventId());
            entity.setLanguage(response.getLanguage());
            entity.setStyle(response.getStyle());
            entity.setIncludeRecommendedActions(
                    response.getRecommendedActions() != null && !response.getRecommendedActions().isEmpty());
            entity.setProvider(response.getProvider());
            entity.setModel(response.getModel());
            entity.setEvidenceHash(response.getEvidenceHash());
            entity.setExplanationJson(toJson(response));
            entity.setSummary(response.getSummary());
            entity.setCurrent(true);
            entity.setGenerationCount(1);
            explanationRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist LLM explanation to SQL for eventId={}", response.getEventId(), e);
        }
    }

    /** Serialises an object to its JSON string representation. */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize explanation to JSON", e);
            return "{}";
        }
    }

    /** Returns the value if non-blank, otherwise the fallback. */
    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
