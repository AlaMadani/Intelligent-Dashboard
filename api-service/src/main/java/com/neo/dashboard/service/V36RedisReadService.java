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
        Set<String> ordered = new LinkedHashSet<>();
        try {
            List<String> listItems = redisTemplate.opsForList().range(key, 0, normalizedLimit - 1);
            if (listItems != null) {
                ordered.addAll(listItems.stream().filter(this::hasText).toList());
            }
        } catch (Exception e) {
            log.debug("Redis key={} is not readable as list", key, e);
        }
        if (!ordered.isEmpty()) {
            return List.copyOf(ordered);
        }

        try {
            Set<String> zsetItems = redisTemplate.opsForZSet().reverseRange(key, 0, normalizedLimit - 1);
            if (zsetItems != null) {
                ordered.addAll(zsetItems.stream().filter(this::hasText).toList());
            }
        } catch (Exception e) {
            log.debug("Redis key={} is not readable as sorted set", key, e);
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
