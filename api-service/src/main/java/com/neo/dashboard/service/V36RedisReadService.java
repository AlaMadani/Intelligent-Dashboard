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

@Service
@RequiredArgsConstructor
@Slf4j
public class V36RedisReadService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

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

    public Optional<JsonNode> readJson(String key) {
        return readRaw(key).flatMap(raw -> parseJson(key, raw));
    }

    public <T> Optional<T> readValue(String key, Class<T> type) {
        return readRaw(key).flatMap(raw -> parseValue(key, raw, type));
    }

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

    public List<JsonNode> readJsonItems(String key, int limit) {
        List<String> rawItems = readCollectionStrings(key, limit);
        if (!rawItems.isEmpty()) {
            List<JsonNode> result = new ArrayList<>(rawItems.size());
            for (String raw : rawItems) {
                parseJson(key, raw).ifPresent(result::add);
            }
            return result;
        }

        return readJson(key)
                .map(this::extractJsonItems)
                .orElseGet(List::of)
                .stream()
                .limit(normalizeLimit(limit))
                .toList();
    }

    public <T> List<T> readItems(String key, Class<T> type, int limit) {
        List<String> rawItems = readCollectionStrings(key, limit);
        if (!rawItems.isEmpty()) {
            List<T> result = new ArrayList<>(rawItems.size());
            for (String raw : rawItems) {
                parseValue(key, raw, type).ifPresent(result::add);
            }
            return result;
        }

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

    private List<String> readCollectionStrings(String key, int limit) {
        if (!hasText(key)) {
            return List.of();
        }
        int normalizedLimit = normalizeLimit(limit);
        List<String> rawList = List.of();
        try {
            List<String> listItems = redisTemplate.opsForList().range(key, 0, normalizedLimit - 1);
            if (listItems != null) {
                rawList = listItems.stream().filter(this::hasText).toList();
            }
        } catch (Exception e) {
            log.debug("Redis key={} is not readable as list", key, e);
        }
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

    private <T> Optional<T> parseValue(String key, String raw, Class<T> type) {
        if (!hasText(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, type));
        } catch (Exception e) {
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

    private List<JsonNode> arrayItems(JsonNode node) {
        ArrayNode array = (ArrayNode) node;
        List<JsonNode> items = new ArrayList<>(array.size());
        array.forEach(items::add);
        return items;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 100;
        }
        return Math.min(limit, 500);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
