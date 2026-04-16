package com.neo.dashboard.service;

import com.neo.dashboard.redis.CacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Reads the live per-session insight payload cached by the Data Processor while
 * a session is still open ({@code session:insight:{insuredId}:{sessionId}}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionInsightReadService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<JsonNode> getInsight(String insuredId, String sessionId) {
        if (insuredId == null || insuredId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String cached = redisTemplate.opsForValue().get(CacheKeys.sessionInsightKey(insuredId, sessionId));
        if (cached == null || cached.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(cached));
        } catch (Exception e) {
            log.warn("Failed to parse session insight insuredId={} sessionId={}", insuredId, sessionId, e);
            return Optional.empty();
        }
    }
}
