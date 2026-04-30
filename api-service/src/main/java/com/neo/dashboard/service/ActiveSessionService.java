package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.ActiveSessionDto;
import com.neo.dashboard.redis.CacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the live per-session insight cache and exposes a typed active-session view.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActiveSessionService {

    private static final int DEFAULT_LIMIT = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public List<ActiveSessionDto> getActiveSessions(String insuredId, Boolean anomalyOnly, Integer limit) {
        Set<String> keys = sessionInsightKeys(insuredId);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        List<ActiveSessionDto> sessions = new ArrayList<>(keys.size());
        for (String key : keys) {
            Optional<ActiveSessionDto> insight = readInsight(key);
            if (insight.isPresent()) {
                sessions.add(insight.get());
                continue;
            }
            removeStaleIndexEntry(key);
        }

        return sessions.stream()
                .filter(session -> insuredId == null || insuredId.isBlank() || insuredId.equals(session.getInsuredId()))
                .filter(session -> !Boolean.TRUE.equals(anomalyOnly) || isAnomalous(session))
                .sorted(Comparator
                        .comparing((ActiveSessionDto session) -> numeric(session.getRiskScore())).reversed()
                        .thenComparing(ActiveSessionDto::getComputedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(normalizeLimit(limit))
                .toList();
    }

    public Optional<ActiveSessionDto> getSessionInsight(String insuredId, String sessionId) {
        if (!hasText(insuredId) || !hasText(sessionId)) {
            return Optional.empty();
        }
        return readInsight(CacheKeys.sessionInsightKey(insuredId, sessionId));
    }

    private Optional<ActiveSessionDto> readInsight(String key) {
        String cached = redisTemplate.opsForValue().get(key);
        if (cached == null || cached.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(cached, ActiveSessionDto.class));
        } catch (Exception e) {
            log.warn("Failed to parse active session insight key={}", key, e);
            return Optional.empty();
        }
    }

    private String resolvePattern(String insuredId) {
        return hasText(insuredId) ? CacheKeys.sessionInsightPattern(insuredId) : CacheKeys.sessionInsightPattern();
    }

    private String resolveIndexKey(String insuredId) {
        return hasText(insuredId)
                ? CacheKeys.activeSessionInsightsIndexKey(insuredId)
                : CacheKeys.activeSessionInsightsIndexKey();
    }

    private Set<String> sessionInsightKeys(String insuredId) {
        Set<String> indexedKeys = redisTemplate.opsForSet().members(resolveIndexKey(insuredId));
        if (indexedKeys != null && !indexedKeys.isEmpty()) {
            return indexedKeys;
        }

        Set<String> scannedKeys = redisTemplate.keys(resolvePattern(insuredId));
        if (scannedKeys == null || scannedKeys.isEmpty()) {
            return Set.of();
        }

        for (String key : scannedKeys) {
            redisTemplate.opsForSet().add(CacheKeys.activeSessionInsightsIndexKey(), key);
            String indexedInsuredId = insuredIdFromInsightKey(key);
            if (indexedInsuredId != null) {
                redisTemplate.opsForSet().add(CacheKeys.activeSessionInsightsIndexKey(indexedInsuredId), key);
            }
        }

        return scannedKeys;
    }

    private boolean isAnomalous(ActiveSessionDto session) {
        if (session == null) {
            return false;
        }
        return Boolean.TRUE.equals(session.getAnomalyFlag())
                || Boolean.TRUE.equals(session.getBinaryAnomaly())
                || Boolean.TRUE.equals(session.getPathDeviationFlag())
                || (session.getPathDeviation() != null && Boolean.TRUE.equals(session.getPathDeviation().getDeviated()))
                || (session.getAnomalyEventCount() != null && session.getAnomalyEventCount() > 0);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, DEFAULT_LIMIT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private double numeric(Double value) {
        return value == null ? 0.0 : value;
    }

    private void removeStaleIndexEntry(String key) {
        if (!hasText(key)) {
            return;
        }
        redisTemplate.opsForSet().remove(CacheKeys.activeSessionInsightsIndexKey(), key);
        String insuredId = insuredIdFromInsightKey(key);
        if (insuredId != null) {
            redisTemplate.opsForSet().remove(CacheKeys.activeSessionInsightsIndexKey(insuredId), key);
        }
    }

    private String insuredIdFromInsightKey(String key) {
        String prefix = "session:insight:";
        if (key == null || !key.startsWith(prefix)) {
            return null;
        }
        String remainder = key.substring(prefix.length());
        int separator = remainder.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        return remainder.substring(0, separator);
    }
}
