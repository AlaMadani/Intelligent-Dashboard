package com.noveocare.dataprocessor.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Small Redis utility that centralizes JSON serialization and counter updates.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RedisCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /* -- JSON string operations -- */

    /* Store an object as a JSON string with a TTL. */
    public void setJson(String key, Object value, Duration ttl) {
        try {
            /* Store objects as JSON strings so non-Java consumers can read the same cache entries. */
            String payload = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, payload, ttl);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize value for Redis key={}", key, e);
        }
    }

    /* Append a JSON-serialised value to a list with a TTL. */
    public void addToJsonList(String key, Object value, Duration ttl) {
        try {
            /* Lists are used for ordered buffers such as session event timelines. */
            String payload = objectMapper.writeValueAsString(value);
            redisTemplate.opsForList().rightPush(key, payload);
            redisTemplate.expire(key, ttl);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize list value for Redis key={}", key, e);
        }
    }

    /* Deserialize every list entry independently so one bad payload does not poison the whole result. */
    public <T> List<T> getJsonList(String key, Class<T> type) {
        /* Deserialize every list entry independently so one bad payload does not poison the whole result. */
        List<String> values = redisTemplate.opsForList().range(key, 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> result = new java.util.ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                result.add(objectMapper.readValue(value, type));
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize Redis list entry for key={}", key, e);
            }
        }
        return result;
    }

    /* Delete a key from Redis. */
    public void deleteKey(String key) {
        redisTemplate.delete(key);
    }

    /* Check if a key exists. */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /* Retrieve and deserialize a single JSON value by class. */
    public <T> T getJson(String key, Class<T> type) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize Redis value for key={}", key, e);
            return null;
        }
    }

    /* Retrieve and deserialize a single JSON value by TypeReference (e.g. for generics). */
    public <T> T getJson(String key, TypeReference<T> type) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize Redis value for key={}", key, e);
            return null;
        }
    }

    /* -- Counter operations -- */

    /* Atomically increment a key and set TTL only when the counter is first created. */
    public void increment(String key, long delta, Duration ttl) {
        /* Apply the TTL only when the counter is created on this increment. */
        Long updated = redisTemplate.opsForValue().increment(key, delta);
        if (updated != null && updated == delta) {
            redisTemplate.expire(key, ttl);
        }
    }

    /* Atomically increment a hash field and set TTL on first write. */
    public void incrementHash(String key, String field, long delta, Duration ttl) {
        /* Use the same "set TTL on first write" rule for hash-based aggregations. */
        Long updated = redisTemplate.opsForHash().increment(key, field, delta);
        if (updated != null && updated == delta) {
            redisTemplate.expire(key, ttl);
        }
    }

    /* -- Pub / Sub -- */

    /* Serialise an object as JSON and publish it to a Redis channel. */
    public void publishJson(String channel, Object value) {
        if (channel == null || channel.isBlank() || value == null) {
            return;
        }
        try {
            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize pubsub payload for Redis channel={}", channel, e);
        }
    }

    /* -- Set operations -- */

    public void addSetMember(String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        redisTemplate.opsForSet().add(key, value);
    }

    public void removeSetMember(String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        redisTemplate.opsForSet().remove(key, value);
    }

    public Set<String> getSetMembers(String key) {
        if (key == null || key.isBlank()) {
            return Set.of();
        }
        Set<String> members = redisTemplate.opsForSet().members(key);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(members);
    }

    /* -- Sorted-set operations -- */

    public boolean zsetAdd(String key, String member, double score) {
        return Boolean.TRUE.equals(redisTemplate.opsForZSet().add(key, member, score));
    }

    public Double zsetScore(String key, String member) {
        return redisTemplate.opsForZSet().score(key, member);
    }

    public Set<String> zsetReverseRange(String key, long start, long end) {
        Set<String> members = redisTemplate.opsForZSet().reverseRange(key, start, end);
        return members != null ? members : Set.of();
    }

    public Set<String> zsetRange(String key, long start, long end) {
        Set<String> members = redisTemplate.opsForZSet().range(key, start, end);
        return members != null ? members : Set.of();
    }

    public Long zsetRemove(String key, Object... members) {
        return redisTemplate.opsForZSet().remove(key, members);
    }

    public Long zsetCard(String key) {
        Long count = redisTemplate.opsForZSet().zCard(key);
        return count != null ? count : 0L;
    }

    public Long zsetRemoveRangeByRank(String key, long start, long end) {
        return redisTemplate.opsForZSet().removeRange(key, start, end);
    }
}
