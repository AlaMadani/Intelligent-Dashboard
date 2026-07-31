package com.noveocare.dataprocessor.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.config.AiLiveSessionProperties;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.config.SessionFirstEvent;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the Redis-backed in-memory event buffers for live sessions,
 * providing deduplication, append, retrieval, and expiry operations.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RedisSessionBufferService {

    /* -- Dependencies -- */
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisCacheProperties cacheProperties;
    private final AiLiveSessionProperties liveSessionProperties;

    /* Tracks how many duplicate event appends were skipped. */
    private final AtomicLong duplicateSequenceAppendsSkipped = new AtomicLong();

    private static final Logger perfLog = LoggerFactory.getLogger("com.noveocare.dataprocessor.redis.RedisSessionBufferService");

    /* -- Public API -- */

    /* Append an event to the session buffers after deduplication. */
    public void appendEvent(AuditTrailEvent event) {
        String key = CacheKeys.sessionKey(event.getInsuredId(), event.getSessionId());
        String fullKey = CacheKeys.sessionFullKey(event.getInsuredId(), event.getSessionId());
        String eventIdsKey = CacheKeys.sessionSequenceEventIdsKey(event.getSessionId());
        String eventId = event.getId() != null ? event.getId() : "";
        try {
            /* -- Deduplication via a SET of seen event IDs -- */
            long dedupeStart = System.currentTimeMillis();
            Long added = redisTemplate.opsForSet().add(eventIdsKey, eventId);
            redisTemplate.expire(eventIdsKey, cacheProperties.getSessionBuffer());
            long dedupeMs = System.currentTimeMillis() - dedupeStart;
            if (dedupeMs > 500) {
                perfLog.warn("Redis slow: sequence append dedupe took {}ms for sessionPrefix={}",
                        dedupeMs, event.getSessionId() != null ? event.getSessionId().substring(0, Math.min(10, event.getSessionId().length())) : "?");
            }
            if (added == null || added == 0) {
                duplicateSequenceAppendsSkipped.incrementAndGet();
                log.debug("Duplicate eventId {} in sequence buffer for session {}, skipping append", eventId, event.getSessionId());
                return;
            }
            String payload = objectMapper.writeValueAsString(event);
            Duration ttl = cacheProperties.getSessionBuffer();

            /* -- Push to the bounded live list and the unbounded full list -- */
            long pushStart = System.currentTimeMillis();

            redisTemplate.opsForList().rightPush(key, payload);
            int maxRetained = liveSessionProperties.getMaxSessionEventsRetained();
            redisTemplate.opsForList().trim(key, -maxRetained, -1);
            redisTemplate.expire(key, ttl);

            redisTemplate.opsForList().rightPush(fullKey, payload);
            redisTemplate.expire(fullKey, ttl);

            long pushMs = System.currentTimeMillis() - pushStart;
            if (pushMs > 500) {
                perfLog.warn("Redis slow: sequence append push took {}ms for sessionPrefix={}",
                        pushMs, event.getSessionId() != null ? event.getSessionId().substring(0, Math.min(10, event.getSessionId().length())) : "?");
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for Redis session buffer", e);
        }
    }

    /* Fetch the most recent N events from the bounded live list. */
    public List<AuditTrailEvent> getRecentSessionEvents(String insuredId, String sessionId, int limit) {
        String key = CacheKeys.sessionKey(insuredId, sessionId);
        List<String> raw = redisTemplate.opsForList().range(key, -limit, -1);
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

    /* Persist or update the first-event metadata for a session. */
    public void persistFirstEvent(String insuredId, String sessionId, AuditTrailEvent event) {
        String key = CacheKeys.sessionFirstEventKey(sessionId);
        String existingJson = redisTemplate.opsForValue().get(key);
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                SessionFirstEvent existing = objectMapper.readValue(existingJson, SessionFirstEvent.class);
                Instant existingTs = existing.getFirstEventTimestamp();
                Instant newTs = event.getCreatedAt();
                Integer existingSeq = existing.getFirstSequenceInSession();
                Integer newSeq = event.getSequenceInSession();
                boolean currentIsEarlier = newTs != null && (existingTs == null || newTs.isBefore(existingTs));
                boolean currentIsLowerSeq = newSeq != null && (existingSeq == null || newSeq < existingSeq);
                if (!currentIsEarlier && !currentIsLowerSeq) {
                    return;
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse existing first event for session {}, overwriting", sessionId, e);
            }
        }
        SessionFirstEvent first = SessionFirstEvent.builder()
                .insuredId(insuredId)
                .sessionId(sessionId)
                .firstEventTimestamp(event.getCreatedAt())
                .firstSequenceInSession(event.getSequenceInSession())
                .firstIp(event.getIp())
                .firstDevice(event.getDevice())
                .firstAction(event.getAction())
                .firstRoute(event.getRoute())
                .firstCountry(event.getCountryCode())
                .firstStatus(event.getStatus())
                .firstHour(event.getCreatedAt() == null ? 0 : event.getCreatedAt().atZone(java.time.ZoneOffset.UTC).getHour())
                .firstDayOfWeek(event.getCreatedAt() == null ? 0 : event.getCreatedAt().atZone(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() - 1)
                .build();
        try {
            String json = objectMapper.writeValueAsString(first);
            redisTemplate.opsForValue().set(key, json, cacheProperties.getSessionBuffer());
            log.debug("Persisted/updated first event metadata for session {}", sessionId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize first event metadata for session {}", sessionId, e);
        }
    }

    /* Retrieve the cached first-event metadata for a session. */
    public SessionFirstEvent getFirstEvent(String sessionId) {
        String key = CacheKeys.sessionFirstEventKey(sessionId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SessionFirstEvent.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize first event metadata for session {}", sessionId, e);
            return null;
        }
    }

    /* Atomically replace the entire session buffer contents. */
    public void replaceSession(String insuredId, String sessionId, List<AuditTrailEvent> events) {
        String key = CacheKeys.sessionKey(insuredId, sessionId);
        String fullKey = CacheKeys.sessionFullKey(insuredId, sessionId);
        String eventIdsKey = CacheKeys.sessionSequenceEventIdsKey(sessionId);
        String summaryKey = CacheKeys.sessionRunningSummaryKey(sessionId);
        redisTemplate.delete(key);
        redisTemplate.delete(fullKey);
        redisTemplate.delete(eventIdsKey);
        redisTemplate.delete(summaryKey);
        for (AuditTrailEvent event : events) {
            appendEvent(event);
        }
    }

    /* Fetch all events from the full-history list, falling back to the bounded list. */
    public List<AuditTrailEvent> getSessionEvents(String insuredId, String sessionId) {
        String fullKey = CacheKeys.sessionFullKey(insuredId, sessionId);
        List<String> raw = redisTemplate.opsForList().range(fullKey, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return getSessionEventsFallback(insuredId, sessionId);
        }
        List<AuditTrailEvent> events = new ArrayList<>(raw.size());
        for (String item : raw) {
            try {
                events.add(objectMapper.readValue(item, AuditTrailEvent.class));
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize event from Redis full list", e);
            }
        }
        return events;
    }

    /* Fallback: read events from the bounded live list when the full list is empty. */
    private List<AuditTrailEvent> getSessionEventsFallback(String insuredId, String sessionId) {
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
                log.error("Failed to deserialize event from Redis live list", e);
            }
        }
        return events;
    }

    /* Update the TTL on the bounded session event list. */
    public void expireSession(String insuredId, String sessionId, Duration ttl) {
        String key = CacheKeys.sessionKey(insuredId, sessionId);
        redisTemplate.expire(key, ttl);
    }

    /* Delete all session buffer keys from Redis. */
    public void deleteSession(String insuredId, String sessionId) {
        String key = CacheKeys.sessionKey(insuredId, sessionId);
        String fullKey = CacheKeys.sessionFullKey(insuredId, sessionId);
        String eventIdsKey = CacheKeys.sessionSequenceEventIdsKey(sessionId);
        String summaryKey = CacheKeys.sessionRunningSummaryKey(sessionId);
        redisTemplate.delete(key);
        redisTemplate.delete(fullKey);
        redisTemplate.delete(eventIdsKey);
        redisTemplate.delete(summaryKey);
    }

    /* -- Metrics -- */

    public long getDuplicateSequenceAppendsSkipped() {
        return duplicateSequenceAppendsSkipped.get();
    }
}