package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlidingWindowService {

    private static final long WINDOW_TTL_MINUTES = 10;
    private static final int MAX_SEQUENCE_LENGTH = 5;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void addEventToUserHistory(AuditTrailEvent event) {
        String redisKey = "history:user:" + event.getUserKey();

        try {
            // Serialize the full event payload.
            String eventJson = objectMapper.writeValueAsString(event);

            // Append to the right end of the list.
            redisTemplate.opsForList().rightPush(redisKey, eventJson);

            // Trim to the most recent window.
            redisTemplate.opsForList().trim(redisKey, -MAX_SEQUENCE_LENGTH, -1);

            // Refresh the idle TTL.
            redisTemplate.expire(redisKey, Duration.ofMinutes(WINDOW_TTL_MINUTES));

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for Redis storage", e);
        }
    }

    // Load and deserialize the most recent events.
    public List<AuditTrailEvent> getRecentEvents(String userKey, int count) {
        String redisKey = "history:user:" + userKey;

        // Fetch the last N JSON elements from Redis.
        List<String> eventsJson = redisTemplate.opsForList().range(redisKey, -count, -1);

        if (eventsJson == null || eventsJson.isEmpty()) {
            return List.of();
        }

        // Deserialize each JSON element into an AuditTrailEvent.
        List<AuditTrailEvent> events = new ArrayList<>();
        for (String json : eventsJson) {
            try {
                events.add(objectMapper.readValue(json, AuditTrailEvent.class));
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize event from Redis", e);
            }
        }

        return events;
    }
}
