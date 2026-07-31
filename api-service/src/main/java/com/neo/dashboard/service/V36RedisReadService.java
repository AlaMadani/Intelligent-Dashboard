package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Low-level Redis read/write service for V3.6.1 data.  Provides type-safe
 * methods for reading raw strings, JSON trees, typed objects, list items,
 * and sorted-set alert payloads.  Handles deserialisation, double-encoded
 * JSON unwrapping, and duplicate-item deduplication transparently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class V36RedisReadService {

    /** Redis template for direct key-value, list, and sorted-set operations. */
    private final StringRedisTemplate redisTemplate;
    /** Jackson mapper for JSON parsing and object conversion. */
    private final ObjectMapper objectMapper;

    /**
     * Reads the raw string value stored at the given Redis key.
     *
     * @param key the Redis key
     * @return an Optional containing the raw string, or empty if the key does
     *         not exist, is blank, or an error occurs
     */
    public Optional<String> readRaw(String key) {
        if (!hasText(key)) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (Exception e) {
            log.warn("Failed to read Redis key={}", key, e);
            return Optional.empty();
        }
    }

    /**
     * Reads a JSON value from Redis and parses it into a {@link JsonNode}.
     *
     * @param key the Redis key
     * @return an Optional containing the parsed JSON tree, or empty
     */
    public Optional<JsonNode> readJson(String key) {
        return readRaw(key).flatMap(raw -> parseJson(key, raw));
    }

    /**
     * Reads a value from Redis and deserialises it into the given target type.
     * Automatically unwraps double-encoded JSON strings.
     *
     * @param key  the Redis key
     * @param type the target class
     * @param <T>  the target type
     * @return an Optional containing the deserialised value, or empty
     */
    public <T> Optional<T> readValue(String key, Class<T> type) {
        return readRaw(key).flatMap(raw -> parseValue(key, raw, type));
    }

    /**
     * Converts a {@link JsonNode} (already parsed) into the given target type
     * using Jackson's tree-to-value conversion.
     *
     * @param node the JSON node to convert
     * @param type the target class
     * @param <T>  the target type
     * @return an Optional containing the converted value, or empty
     */
    public <T> Optional<T> convert(JsonNode node, Class<T> type) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.treeToValue(node, type));
        } catch (Exception e) {
            log.warn("Failed to convert JSON node to {}", type.getSimpleName(), e);
            return Optional.empty();
        }
    }

    /**
     * Reads items from a Redis list or sorted set as JSON nodes, limited to
     * the specified count.  Falls back to extracting items from a single JSON
     * value when the collection key is not a list/ZSET.
     *
     * @param key   the Redis key
     * @param limit maximum number of items to return
     * @return a list of parsed JSON nodes
     */
    public List<JsonNode> readJsonItems(String key, int limit) {
        /* First, try to read as a collection (list or ZSET). */
        List<String> rawItems = readCollectionStrings(key, limit);
        if (!rawItems.isEmpty()) {
            List<JsonNode> result = new ArrayList<>(rawItems.size());
            for (String raw : rawItems) {
                parseJson(key, raw).ifPresent(result::add);
            }
            return result;
        }

        /* Fall back to reading a single JSON value and extracting its items. */
        return readJson(key)
                .map(this::extractJsonItems)
                .orElseGet(List::of)
                .stream()
                .limit(normalizeLimit(limit))
                .toList();
    }

    /**
     * Reads items from a Redis list or sorted set, deserialising each into the
     * given type.  Falls back to extracting items from a single JSON value when
     * the collection key is not a list/ZSET.
     *
     * @param key   the Redis key
     * @param type  the target item class
     * @param limit maximum number of items to return
     * @param <T>   the target type
     * @return a list of deserialised items
     */
    public <T> List<T> readItems(String key, Class<T> type, int limit) {
        /* First, try to read as a collection (list or ZSET). */
        List<String> rawItems = readCollectionStrings(key, limit);
        if (!rawItems.isEmpty()) {
            List<T> result = new ArrayList<>(rawItems.size());
            for (String raw : rawItems) {
                parseValue(key, raw, type).ifPresent(result::add);
            }
            return result;
        }

        /* Fall back to reading a single JSON value and extracting its items. */
        return readJson(key)
                .map(this::extractJsonItems)
                .orElseGet(List::of)
                .stream()
                .limit(normalizeLimit(limit))
                .map(item -> convert(item, type))
                .flatMap(Optional::stream)
                .toList();
    }

    private static final int MAX_ZSET_FETCH = 5000;

    /**
     * Read alerts from a canonical ZSET key where the member is the eventId
     * and the full payload is stored at a separate payload key.
     * Returns items in ZREVRANGE order (highest score first = newest).
     */
    /**
     * Reads alert items from a canonical Redis sorted set (ZSET) where each
     * member is an event ID and the full payload is stored at a separate key
     * ({@code payloadKeyPrefix + member}).  Returns items in ZREVRANGE order
     * (newest first).
     *
     * @param zsetKey          the Redis ZSET key containing event IDs as members
     * @param payloadKeyPrefix the prefix for per-event payload keys
     * @param type             the target class for deserialisation
     * @param limit            maximum number of items to fetch (capped at {@value #MAX_ZSET_FETCH})
     * @param <T>              the target type
     * @return a list of deserialised alert items, newest first
     */
    public <T> List<T> readZSetAlertItems(String zsetKey, String payloadKeyPrefix, Class<T> type, int limit) {
        if (!hasText(zsetKey)) return List.of();
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_ZSET_FETCH));
        Set<String> members;
        try {
            members = redisTemplate.opsForZSet().reverseRange(zsetKey, 0, normalizedLimit - 1);
        } catch (Exception e) {
            log.debug("Redis key={} is not readable as sorted set", zsetKey, e);
            return List.of();
        }
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        /* Read the full payload for each member ID. */
        List<T> result = new ArrayList<>(members.size());
        int skipped = 0;
        for (String member : members) {
            if (!hasText(member)) { skipped++; continue; }
            String payloadKey = payloadKeyPrefix + member;
            Optional<T> value = readValue(payloadKey, type);
            if (value.isPresent()) {
                result.add(value.get());
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.debug("Skipped {} ZSET members with missing/unparseable payloads for key={}", skipped, zsetKey);
        }
        return result;
    }

    /**
     * Returns the number of items in a Redis list or sorted set.  Tries list
     * size first, then ZSET cardinality.
     *
     * @param key the Redis key
     * @return the item count, or 0 if the key does not exist or is not a collection
     */
    public Long countItems(String key) {
        if (!hasText(key)) return 0L;
        try {
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size > 0) return size;
        } catch (Exception e) { /* not a list */ }
        try {
            Long size = redisTemplate.opsForZSet().size(key);
            if (size != null) return size;
        } catch (Exception e) { /* not a zset */ }
        return 0L;
    }

    /**
     * Writes a JSON-serialised value to Redis with an optional TTL.  If the
     * value is already a string it is stored as-is; otherwise it is serialised
     * via Jackson.
     *
     * @param key   the Redis key
     * @param value the value to serialise (or a pre-serialised string)
     * @param ttl   the time-to-live duration; {@code null} or non-positive means no expiry
     */
    public void writeJson(String key, Object value, java.time.Duration ttl) {
        if (!hasText(key) || value == null) {
            return;
        }
        try {
            String json = value instanceof String text ? text : objectMapper.writeValueAsString(value);
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                redisTemplate.opsForValue().set(key, json);
            } else {
                redisTemplate.opsForValue().set(key, json, ttl);
            }
        } catch (Exception e) {
            log.warn("Failed to write Redis JSON key={}", key, e);
        }
    }

    /**
     * Reads raw string items from a Redis key, attempting list first and
     * falling back to sorted set (ZREVRANGE).  Deduplicates items while
     * preserving insertion order and logs a warning when duplicates are found.
     *
     * @param key   the Redis key
     * @param limit maximum number of items to return
     * @return a deduplicated list of raw string items
     */
    private List<String> readCollectionStrings(String key, int limit) {
        if (!hasText(key)) {
            return List.of();
        }
        int normalizedLimit = normalizeLimit(limit);
        List<String> rawList = List.of();
        /* Try reading as a Redis list first. */
        try {
            List<String> listItems = redisTemplate.opsForList().range(key, 0, normalizedLimit - 1);
            if (listItems != null) {
                rawList = listItems.stream().filter(this::hasText).toList();
            }
        } catch (Exception e) {
            log.debug("Redis key={} is not readable as list", key, e);
        }
        /* Fall back to reading as a sorted set (ZREVRANGE). */
        if (rawList.isEmpty()) {
            try {
                Set<String> zsetItems = redisTemplate.opsForZSet().reverseRange(key, 0, normalizedLimit - 1);
                if (zsetItems != null) {
                    rawList = List.copyOf(zsetItems.stream().filter(this::hasText).toList());
                }
            } catch (Exception e) {
                log.debug("Redis key={} is not readable as sorted set", key, e);
            }
        }
        if (rawList.isEmpty()) {
            return List.of();
        }
        /* Deduplicate while preserving order. */
        Set<String> ordered = new LinkedHashSet<>();
        AtomicInteger duplicateCount = new AtomicInteger(0);
        for (String item : rawList) {
            if (!ordered.add(item)) {
                duplicateCount.incrementAndGet();
            }
        }
        if (duplicateCount.get() > 0) {
            log.warn("Redis key={} contains {} duplicate items (read-side protection)", key, duplicateCount.get());
        }
        return List.copyOf(ordered);
    }

    /**
     * Parses a raw JSON string into a {@link JsonNode}.
     *
     * @param key the Redis key (used in warning messages)
     * @param raw the raw JSON string
     * @return an Optional containing the parsed node, or empty on parse failure
     */
    private Optional<JsonNode> parseJson(String key, String raw) {
        if (!hasText(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(raw));
        } catch (Exception e) {
            log.warn("Malformed JSON in Redis key={}", key, e);
            return Optional.empty();
        }
    }

    /**
     * Parses and deserialises a raw JSON string into the given target type.
     * Handles double-encoded JSON by unwrapping an outer textual node, and
     * falls back to tree-to-value conversion if direct deserialisation fails.
     *
     * @param key  the Redis key (used in warning messages)
     * @param raw  the raw JSON string
     * @param type the target class
     * @param <T>  the target type
     * @return an Optional containing the deserialised value, or empty
     */
    private <T> Optional<T> parseValue(String key, String raw, Class<T> type) {
        if (!hasText(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, type));
        } catch (Exception e) {
            /* Direct deserialisation failed — try unwrapping double-encoded JSON. */
            try {
                JsonNode node = objectMapper.readTree(raw);
                if (node != null && node.isTextual()) {
                    String inner = node.asText();
                    if (hasText(inner)) {
                        log.debug("Unwrapped double-encoded JSON for Redis key={}", key);
                        return Optional.of(objectMapper.readValue(inner, type));
                    }
                }
                if (node != null && !node.isNull()) {
                    return Optional.of(objectMapper.treeToValue(node, type));
                }
            } catch (Exception ignored) {}
            log.warn("Malformed JSON in Redis key={} for {}", key, type.getSimpleName(), e);
            return Optional.empty();
        }
    }

    /**
     * Extracts a list of items from a JSON node.  If the node is an array it
     * is returned directly; if it is an object the {@code "items"} or
     * {@code "data"} child arrays are used; otherwise the node itself is
     * returned as a single-element list.
     *
     * @param node the parsed JSON node
     * @return a list of child JSON nodes
     */
    private List<JsonNode> extractJsonItems(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(items::add);
            return items;
        }
        JsonNode items = node.path("items");
        if (items.isArray()) {
            return arrayItems(items);
        }
        JsonNode data = node.path("data");
        if (data.isArray()) {
            return arrayItems(data);
        }
        return List.of(node);
    }

    /** Copies all elements from an {@link ArrayNode} into a mutable list. */
    private List<JsonNode> arrayItems(JsonNode node) {
        ArrayNode array = (ArrayNode) node;
        List<JsonNode> items = new ArrayList<>(array.size());
        array.forEach(items::add);
        return items;
    }

    /**
     * Normalises a user-provided limit to the range [1, 500], defaulting to
     * 100 when the input is less than 1.
     */
    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 100;
        }
        return Math.min(limit, 500);
    }

    /** Returns {@code true} if the string is non-null and contains non-whitespace characters. */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
