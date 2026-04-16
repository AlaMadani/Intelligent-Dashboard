package com.noveocare.dataprocessor.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores the ordered in-flight event list for each insured/session pair.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RedisSessionBufferService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisCacheProperties cacheProperties;

    public void appendEvent(AuditTrailEvent event) {
        // Every event is appended to the right so Redis preserves session order.
        String key = CacheKeys.sessionKey(event.getInsuredId(), event.getSessionId());
        try {
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(key, payload);
            redisTemplate.expire(key, cacheProperties.getSessionBuffer());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for Redis session buffer", e);
        }
    }

    public void replaceSession(String insuredId, String sessionId, List<AuditTrailEvent> events) {
        String key = CacheKeys.sessionKey(insuredId, sessionId);
        redisTemplate.delete(key);
        for (AuditTrailEvent event : events) {
            appendEvent(event);
        }
    }

    public List<AuditTrailEvent> getSessionEvents(String insuredId, String sessionId) {
        // Rehydrate the full Redis list whenever downstream logic needs the current session timeline.
        String key = CacheKeys.sessionKey(insuredId, sessionId);
        List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<AuditTrailEvent> events = new ArrayList<>(raw.size());
        for (String item : raw) {
            try {
                events.add(objectMapper.readValue(item, AuditTrailEvent.class));
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize event from Redis", e);
            }
        }
        return events;
    }

    public void expireSession(String insuredId, String sessionId, Duration ttl) {
        // Allow callers to extend or shrink the session lifetime without touching the payload.
        String key = CacheKeys.sessionKey(insuredId, sessionId);
        redisTemplate.expire(key, ttl);
    }

    public void deleteSession(String insuredId, String sessionId) {
        // Clear the buffer once the session-close workflow has completed.
        String key = CacheKeys.sessionKey(insuredId, sessionId);
        redisTemplate.delete(key);
    }
}
