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
        Set<String> keys = redisTemplate.keys(resolvePattern(insuredId));
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        List<ActiveSessionDto> sessions = new ArrayList<>(keys.size());
        for (String key : keys) {
            readInsight(key).ifPresent(sessions::add);
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

    private boolean isAnomalous(ActiveSessionDto session) {
        if (session == null) {
            return false;
        }
        return Boolean.TRUE.equals(session.getAnomalyFlag())
                || Boolean.TRUE.equals(session.getBinaryAnomaly())
                || (session.getPathDeviation() != null && Boolean.TRUE.equals(session.getPathDeviation().getDeviated()));
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
}
