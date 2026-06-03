package com.neo.dashboard.service;

import com.neo.dashboard.dto.v36.V36LlmExplanationResponse;
import com.neo.dashboard.redis.CacheKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LlmExplanationCacheService {

    private final V36RedisReadService redisReadService;

    @Value("${app.v36.explanations.cache-enabled:true}")
    private boolean cacheEnabled;

    @Value("${app.v36.explanations.cache-ttl-hours:24}")
    private long cacheTtlHours;

    public Optional<V36LlmExplanationResponse> getLatest(String eventId) {
        if (!cacheEnabled) {
            return Optional.empty();
        }
        return redisReadService.readValue(CacheKeys.explanationV36LatestKey(eventId), V36LlmExplanationResponse.class)
                .map(response -> {
                    response.setCached(true);
                    return response;
                });
    }

    public Optional<V36LlmExplanationResponse> get(String eventId, String evidenceHash, String style, String language) {
        if (!cacheEnabled) {
            return Optional.empty();
        }
        return redisReadService.readValue(CacheKeys.explanationV36Key(eventId, evidenceHash, style, language), V36LlmExplanationResponse.class)
                .map(response -> {
                    response.setCached(true);
                    return response;
                });
    }

    public void put(V36LlmExplanationResponse response) {
        if (!cacheEnabled || response == null || response.getEventId() == null) {
            return;
        }
        Duration ttl = Duration.ofHours(Math.max(cacheTtlHours, 1));
        redisReadService.writeJson(
                CacheKeys.explanationV36Key(response.getEventId(), response.getEvidenceHash(), response.getStyle(), response.getLanguage()),
                response,
                ttl
        );
        redisReadService.writeJson(CacheKeys.explanationV36LatestKey(response.getEventId()), response, ttl);
    }
}
