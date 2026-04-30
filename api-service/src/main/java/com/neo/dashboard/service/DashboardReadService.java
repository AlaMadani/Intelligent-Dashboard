package com.neo.dashboard.service;

import com.neo.dashboard.redis.CacheKeys;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
@Slf4j
public class DashboardReadService {

    private static final Set<String> ITEM_LIST_VIEWS = Set.of(
            "alerts",
            "risky-sessions",
            "cluster-mix",
            "drop-offs",
            "path-deviations"
    );

    private static final Set<String> OBJECT_VIEWS = Set.of(
            "forecasts",
            "forecast-series"
    );

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

    public DashboardReadService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<JsonNode> getSnapshot(String view) {
        if (!isAllowedView(view)) {
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

    public Optional<JsonNode> getSnapshotOrDefault(String view) {
        if (!isAllowedView(view)) {
            return Optional.empty();
        }
        return getSnapshot(view).or(() -> Optional.of(defaultSnapshot(view)));
    }

    private boolean isAllowedView(String view) {
        return view != null && !view.isBlank() && ALLOWED_VIEWS.contains(view);
    }

    private JsonNode defaultSnapshot(String view) {
        if (ITEM_LIST_VIEWS.contains(view)) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("items", objectMapper.createArrayNode());
            return payload;
        }
        return objectMapper.createObjectNode();
    }
}
