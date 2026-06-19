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

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmExplanationCacheService {

    private final V36RedisReadService redisReadService;
    private final StringRedisTemplate redisTemplate;
    private final LlmExplanationRepository explanationRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.v36.explanations.cache-enabled:true}")
    private boolean cacheEnabled;

    @Value("${app.v36.explanations.cache-ttl-hours:24}")
    private long cacheTtlHours;

    public Optional<V36LlmExplanationResponse> getLatest(String eventId) {
        if (!cacheEnabled || eventId == null) {
            return Optional.empty();
        }
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

    public Optional<V36LlmExplanationResponse> get(String eventId, String evidenceHash, String style, String language) {
        if (!cacheEnabled || eventId == null) {
            return Optional.empty();
        }
        Optional<V36LlmExplanationResponse> redis = redisReadService.readValue(
                CacheKeys.explanationV36Key(eventId, evidenceHash, style, language), V36LlmExplanationResponse.class)
                .map(response -> {
                    response.setCached(true);
                    response.setSource("redis");
                    return response;
                });
        if (redis.isPresent()) {
            log.info("LLM_EXPLANATION_CACHE_HIT source=redis eventId={}", eventId);
            return redis;
        }
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

    @Transactional
    public void put(V36LlmExplanationResponse response) {
        if (!cacheEnabled || response == null || response.getEventId() == null) {
            return;
        }
        Duration ttl = Duration.ofHours(Math.max(cacheTtlHours, 1));
        redisReadService.writeJson(
                CacheKeys.explanationV36Key(response.getEventId(), response.getEvidenceHash(),
                        response.getStyle(), response.getLanguage()),
                response, ttl);
        redisReadService.writeJson(CacheKeys.explanationV36LatestKey(response.getEventId()), response, ttl);

        saveToSql(response);

        log.info("LLM_EXPLANATION_STORED eventId={} redis=true sql=true", response.getEventId());
    }

    public boolean tryAcquireLock(String eventId, String language, String style, Duration ttl) {
        String key = CacheKeys.explanationLockKey(eventId, language, style);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "locked", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseLock(String eventId, String language, String style) {
        String key = CacheKeys.explanationLockKey(eventId, language, style);
        redisTemplate.delete(key);
    }

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

    private Optional<V36LlmExplanationResponse> readFromSql(String eventId, String evidenceHash, String style, String language) {
        return explanationRepository
                .findTopByEventIdAndLanguageAndStyleAndIncludeRecommendedActionsAndEvidenceHashAndCurrentTrue(
                        eventId, language, style, true, evidenceHash)
                .map(this::toResponse);
    }

    private Optional<V36LlmExplanationResponse> readFromSqlLatest(String eventId) {
        return explanationRepository
                .findTopByEventIdAndCurrentTrueOrderByUpdatedAtDesc(eventId)
                .map(this::toResponse);
    }

    private V36LlmExplanationResponse toResponse(LlmExplanation entity) {
        V36LlmExplanationResponse response;
        try {
            response = objectMapper.readValue(entity.getExplanationJson(), V36LlmExplanationResponse.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize SQL llm_explanation explanation_json for eventId={}", entity.getEventId(), e);
            response = new V36LlmExplanationResponse();
            response.setEventId(entity.getEventId());
            response.setSummary(entity.getSummary());
        }
        if (response.getSchemaVersion() == null) {
            response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        }
        response.setEvidenceHash(entity.getEvidenceHash());
        response.setStyle(entity.getStyle());
        response.setLanguage(entity.getLanguage());
        return response;
    }

    private void saveToSql(V36LlmExplanationResponse response) {
        try {
            explanationRepository.markPreviousAsNotCurrent(
                    response.getEventId(),
                    safe(response.getLanguage(), "en"),
                    safe(response.getStyle(), "security_analyst"),
                    response.getRecommendedActions() != null && !response.getRecommendedActions().isEmpty());

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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize explanation to JSON", e);
            return "{}";
        }
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
