package com.neo.dashboard.service;

import com.neo.dashboard.redis.CacheKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Set;

/**
 * Reads dashboard snapshot blobs written by the Data Processor into
 * {@code dashboard:*} keys.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardReadService {

    private static final Set<String> ALLOWED_VIEWS = Set.of(
            "alerts",
            "risky-sessions",
            "cluster-mix",
            "drop-offs",
            "path-deviations",
            "forecasts",
            "forecast-series"
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<JsonNode> getSnapshot(String view) {
        if (view == null || view.isBlank() || !ALLOWED_VIEWS.contains(view)) {
            return Optional.empty();
        }
        String cached = redisTemplate.opsForValue().get(CacheKeys.dashboardKey(view));
        if (cached == null || cached.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(cached));
        } catch (Exception e) {
            log.warn("Failed to parse dashboard snapshot view={}", view, e);
            return Optional.empty();
        }
    }
}
