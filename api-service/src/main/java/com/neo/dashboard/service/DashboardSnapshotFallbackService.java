package com.neo.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.entity.DashboardSnapshot;
import com.neo.dashboard.repository.DashboardSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardSnapshotFallbackService {

    private static final Duration REDIS_REHYDRATE_TTL = Duration.ofHours(1);

    private final V36RedisReadService redisReadService;
    private final DashboardSnapshotRepository dashboardSnapshotRepository;
    private final ObjectMapper objectMapper;

    public <T> FallbackResult<T> readWithFallback(String redisKey, Class<T> type,
                                                  String viewName, String snapshotKey) {
        Optional<T> redis = redisReadService.readValue(redisKey, type);
        if (redis.isPresent()) {
            return new FallbackResult<>(redis.get(), "redis", null, null);
        }

        FallbackResult<T> sqlResult = readFromSql(type, viewName, snapshotKey);
        if (sqlResult != null) {
            rehydrateRedis(redisKey, sqlResult.payload(), REDIS_REHYDRATE_TTL);
        }
        return sqlResult;
    }

    public <T> FallbackResult<T> readFromSql(Class<T> type, String viewName, String snapshotKey) {
        return dashboardSnapshotRepository.findByViewNameAndSnapshotKey(viewName, snapshotKey)
                .flatMap(snapshot -> parseSnapshot(snapshot, type))
                .map(payload -> new FallbackResult<>(payload, "sql_fallback", null, snapshotKey))
                .orElse(null);
    }

    public <T> List<T> readListWithFallback(String redisKey, Class<T> itemType,
                                            String viewName, String snapshotKey, int limit) {
        List<T> redisItems = redisReadService.readItems(redisKey, itemType, limit);
        if (!redisItems.isEmpty()) {
            return redisItems;
        }

        List<T> sqlItems = readListFromSql(itemType, viewName, snapshotKey, limit);
        if (!sqlItems.isEmpty()) {
            return sqlItems;
        }

        return List.of();
    }

    public <T> List<T> readListFromSql(Class<T> itemType, String viewName,
                                       String snapshotKey, int limit) {
        return dashboardSnapshotRepository.findByViewNameAndSnapshotKey(viewName, snapshotKey)
                .flatMap(snapshot -> parseListSnapshot(snapshot, itemType))
                .map(items -> items.size() > limit ? items.subList(0, limit) : items)
                .orElse(List.of());
    }

    public void rehydrateRedis(String redisKey, Object payload, Duration ttl) {
        try {
            redisReadService.writeJson(redisKey, payload, ttl);
            log.debug("Rehydrated Redis key={} with TTL={}", redisKey, ttl);
        } catch (Exception e) {
            log.warn("Failed to rehydrate Redis key={}", redisKey, e);
        }
    }

    private <T> Optional<T> parseSnapshot(DashboardSnapshot snapshot, Class<T> type) {
        String json = snapshot.getPayloadJson();
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Failed to parse dashboard_snapshot view={} key={} as {}",
                    snapshot.getViewName(), snapshot.getSnapshotKey(), type.getSimpleName(), e);
            return Optional.empty();
        }
    }

    private <T> Optional<List<T>> parseListSnapshot(DashboardSnapshot snapshot, Class<T> itemType) {
        String json = snapshot.getPayloadJson();
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                List<T> items = new ArrayList<>();
                for (JsonNode element : node) {
                    items.add(objectMapper.treeToValue(element, itemType));
                }
                return Optional.of(items);
            }
            JsonNode items = node.path("items");
            if (items.isArray()) {
                List<T> result = new ArrayList<>();
                for (JsonNode element : items) {
                    result.add(objectMapper.treeToValue(element, itemType));
                }
                return Optional.of(result);
            }
            JsonNode data = node.path("data");
            if (data.isArray()) {
                List<T> result = new ArrayList<>();
                for (JsonNode element : data) {
                    result.add(objectMapper.treeToValue(element, itemType));
                }
                return Optional.of(result);
            }
            T single = objectMapper.treeToValue(node, itemType);
            return Optional.of(List.of(single));
        } catch (Exception e) {
            log.warn("Failed to parse dashboard_snapshot list view={} key={} as {}",
                    snapshot.getViewName(), snapshot.getSnapshotKey(), itemType.getSimpleName(), e);
            return Optional.empty();
        }
    }

    public record FallbackResult<T>(T payload, String source, Instant snapshotTimestamp, String snapshotKey) {}
}
