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

/**
 * Provides a Redis-first, SQL-fallback read strategy for dashboard snapshot
 * data. When Redis misses, the payload is loaded from the
 * {@code dashboard_snapshot} table and re-hydrated into Redis for subsequent
 * reads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardSnapshotFallbackService {

    /** Default TTL applied to data re-hydrated into Redis. */
    private static final Duration REDIS_REHYDRATE_TTL = Duration.ofHours(1);

    /** Reads/writes structured data from/to Redis. */
    private final V36RedisReadService redisReadService;
    /** Persisted snapshot repository acting as the durable fallback store. */
    private final DashboardSnapshotRepository dashboardSnapshotRepository;
    /** JSON serialisation/deserialisation helper. */
    private final ObjectMapper objectMapper;

    /**
     * Tries Redis first for a single object; on miss loads from SQL and
     * re-hydrates the cache.
     *
     * @param redisKey    the Redis key to check
     * @param type        the target Java type
     * @param viewName    dashboard view name used for the SQL lookup
     * @param snapshotKey snapshot key used for the SQL lookup
     * @param <T>         the target type
     * @return a FallbackResult describing the payload and its origin, or null
     */
    public <T> FallbackResult<T> readWithFallback(String redisKey, Class<T> type,
                                                  String viewName, String snapshotKey) {
        // Attempt a fast Redis read first
        Optional<T> redis = redisReadService.readValue(redisKey, type);
        if (redis.isPresent()) {
            return new FallbackResult<>(redis.get(), "redis", null, null);
        }

        // Fall through to SQL and re-populate Redis on success
        FallbackResult<T> sqlResult = readFromSql(type, viewName, snapshotKey);
        if (sqlResult != null) {
            rehydrateRedis(redisKey, sqlResult.payload(), REDIS_REHYDRATE_TTL);
        }
        return sqlResult;
    }

    /**
     * Loads a single snapshot from the SQL database by view name + snapshot key.
     *
     * @param type        the target Java type for deserialisation
     * @param viewName    the dashboard view name
     * @param snapshotKey the snapshot key
     * @param <T>         the target type
     * @return a FallbackResult with SQL origin, or null if not found
     */
    public <T> FallbackResult<T> readFromSql(Class<T> type, String viewName, String snapshotKey) {
        return dashboardSnapshotRepository.findByViewNameAndSnapshotKey(viewName, snapshotKey)
                .flatMap(snapshot -> parseSnapshot(snapshot, type))
                .map(payload -> new FallbackResult<>(payload, "sql_fallback", null, snapshotKey))
                .orElse(null);
    }

    /**
     * Tries Redis first for a list of items; on miss loads from SQL.
     *
     * @param redisKey    the Redis key
     * @param itemType    the list element type
     * @param viewName    dashboard view name for the SQL lookup
     * @param snapshotKey snapshot key for the SQL lookup
     * @param limit       maximum number of items to return
     * @param <T>         the list element type
     * @return a list of items (possibly empty)
     */
    public <T> List<T> readListWithFallback(String redisKey, Class<T> itemType,
                                            String viewName, String snapshotKey, int limit) {
        // Try Redis first
        List<T> redisItems = redisReadService.readItems(redisKey, itemType, limit);
        if (!redisItems.isEmpty()) {
            return redisItems;
        }

        // Fall through to SQL
        List<T> sqlItems = readListFromSql(itemType, viewName, snapshotKey, limit);
        if (!sqlItems.isEmpty()) {
            return sqlItems;
        }

        return List.of();
    }

    /**
     * Loads a list of items from the SQL snapshot, parsing the JSON payload
     * as an array or as a container object with {@code items} / {@code data}
     * fields.
     *
     * @param itemType    the list element type
     * @param viewName    dashboard view name
     * @param snapshotKey snapshot key
     * @param limit       maximum items to return
     * @param <T>         the list element type
     * @return a list of deserialised items (possibly empty)
     */
    public <T> List<T> readListFromSql(Class<T> itemType, String viewName,
                                       String snapshotKey, int limit) {
        return dashboardSnapshotRepository.findByViewNameAndSnapshotKey(viewName, snapshotKey)
                .flatMap(snapshot -> parseListSnapshot(snapshot, itemType))
                .map(items -> items.size() > limit ? items.subList(0, limit) : items)
                .orElse(List.of());
    }

    /**
     * Writes a payload into Redis with the given TTL. Failures are logged
     * but never propagated to the caller.
     *
     * @param redisKey the Redis key to write to
     * @param payload  the object to serialise and store
     * @param ttl      the time-to-live for the key
     */
    public void rehydrateRedis(String redisKey, Object payload, Duration ttl) {
        try {
            redisReadService.writeJson(redisKey, payload, ttl);
            log.debug("Rehydrated Redis key={} with TTL={}", redisKey, ttl);
        } catch (Exception e) {
            log.warn("Failed to rehydrate Redis key={}", redisKey, e);
        }
    }

    /**
     * Deserialises the JSON payload of a single-entity snapshot.
     */
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

    /**
     * Deserialises the JSON payload of a list-entity snapshot, handling
     * top-level arrays, {@code items}, {@code data}, or a single object.
     */
    private <T> Optional<List<T>> parseListSnapshot(DashboardSnapshot snapshot, Class<T> itemType) {
        String json = snapshot.getPayloadJson();
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            // Parse once and re-use the tree
            JsonNode node = objectMapper.readTree(json);

            // Case 1: top-level JSON array
            if (node.isArray()) {
                List<T> items = new ArrayList<>();
                for (JsonNode element : node) {
                    items.add(objectMapper.treeToValue(element, itemType));
                }
                return Optional.of(items);
            }
            // Case 2: wrapped under "items"
            JsonNode items = node.path("items");
            if (items.isArray()) {
                List<T> result = new ArrayList<>();
                for (JsonNode element : items) {
                    result.add(objectMapper.treeToValue(element, itemType));
                }
                return Optional.of(result);
            }
            // Case 3: wrapped under "data"
            JsonNode data = node.path("data");
            if (data.isArray()) {
                List<T> result = new ArrayList<>();
                for (JsonNode element : data) {
                    result.add(objectMapper.treeToValue(element, itemType));
                }
                return Optional.of(result);
            }
            // Case 4: single object → wrap in a singleton list
            T single = objectMapper.treeToValue(node, itemType);
            return Optional.of(List.of(single));
        } catch (Exception e) {
            log.warn("Failed to parse dashboard_snapshot list view={} key={} as {}",
                    snapshot.getViewName(), snapshot.getSnapshotKey(), itemType.getSimpleName(), e);
            return Optional.empty();
        }
    }

    /**
     * Carries the deserialised payload together with metadata about its
     * source and the snapshot key.
     */
    public record FallbackResult<T>(T payload, String source, Instant snapshotTimestamp, String snapshotKey) {}
}
