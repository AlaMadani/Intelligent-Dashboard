package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides idempotent event processing by tracking processed events in Redis.
 * Uses a processing marker with short TTL followed by a permanent processed marker.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventIdempotencyService {

    /* Redis key constants */
    private static final String PROCESSING_PREFIX = "processed:event:v3_6:";
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(5);
    private static final Logger perfLog = LoggerFactory.getLogger("com.noveocare.dataprocessor.service.EventIdempotencyService");

    /* Injected dependencies */
    private final StringRedisTemplate redisTemplate;
    private final RedisCacheProperties cacheProperties;

    /* Idempotency metrics */
    private final AtomicLong duplicateEventsSkipped = new AtomicLong();
    private final AtomicReference<String> lastDuplicateEventId = new AtomicReference<>();
    private final AtomicReference<String> lastDuplicateEventSessionId = new AtomicReference<>();
    private final AtomicReference<Instant> lastDuplicateEventAt = new AtomicReference<>();

    /* --- Idempotency check and marking --- */

    public boolean isDuplicate(AuditTrailEvent event) {
        String idempotencyKey = resolveIdempotencyKey(event);
        return Boolean.TRUE.equals(redisTemplate.hasKey(PROCESSING_PREFIX + idempotencyKey));
    }

    public boolean tryMarkProcessing(AuditTrailEvent event) {
        String idempotencyKey = resolveIdempotencyKey(event);
        String key = PROCESSING_PREFIX + idempotencyKey;
        long startMs = System.currentTimeMillis();
        Boolean set = redisTemplate.opsForValue().setIfAbsent(key, "processing", PROCESSING_TTL);
        long elapsedMs = System.currentTimeMillis() - startMs;
        if (elapsedMs > 500) {
            perfLog.warn("Redis slow: idempotency tryMarkProcessing took {}ms for keyPrefix={}",
                    elapsedMs, idempotencyKey.substring(0, Math.min(8, idempotencyKey.length())));
        }
        if (Boolean.TRUE.equals(set)) {
            return true;
        }
        duplicateEventsSkipped.incrementAndGet();
        lastDuplicateEventId.set(event.getId());
        lastDuplicateEventSessionId.set(event.getSessionId());
        lastDuplicateEventAt.set(Instant.now());
        log.debug("Duplicate event detected and skipped: eventId={} insuredId={} sessionId={} action={}",
                event.getId(), event.getInsuredId(), event.getSessionId(), event.getAction());
        return false;
    }

    /* Sets the permanent processed marker after successful handling */
    public void markProcessed(AuditTrailEvent event) {
        String idempotencyKey = resolveIdempotencyKey(event);
        String key = PROCESSING_PREFIX + idempotencyKey;
        long startMs = System.currentTimeMillis();
        redisTemplate.opsForValue().set(key, "processed", cacheProperties.getProcessedEvent());
        long elapsedMs = System.currentTimeMillis() - startMs;
        if (elapsedMs > 500) {
            perfLog.warn("Redis slow: idempotency markProcessed took {}ms for keyPrefix={}",
                    elapsedMs, idempotencyKey.substring(0, Math.min(8, idempotencyKey.length())));
        }
    }

    /* Removes the transient processing marker on failure */
    public void removeProcessingMarker(AuditTrailEvent event) {
        String idempotencyKey = resolveIdempotencyKey(event);
        redisTemplate.delete(PROCESSING_PREFIX + idempotencyKey);
    }

    /* --- Diagnostics --- */

    public long getDuplicateEventsSkipped() {
        return duplicateEventsSkipped.get();
    }

    public String getLastDuplicateEventId() {
        return lastDuplicateEventId.get();
    }

    public String getLastDuplicateEventSessionId() {
        return lastDuplicateEventSessionId.get();
    }

    public Instant getLastDuplicateEventAt() {
        return lastDuplicateEventAt.get();
    }

    /* --- Internal helpers --- */

    private String resolveIdempotencyKey(AuditTrailEvent event) {
        if (event.getId() != null && !event.getId().isBlank()) {
            return event.getId();
        }
        String insuredId = event.getInsuredId() != null ? event.getInsuredId() : "";
        String sessionId = event.getSessionId() != null ? event.getSessionId() : "";
        String timestamp = event.getCreatedAt() != null ? event.getCreatedAt().toString() : "";
        String action = event.getAction() != null ? event.getAction() : "";
        String actionValue = event.getActionValue() != null ? event.getActionValue() : "";
        Integer seq = event.getSessionActionSeq();
        String seqStr = seq != null ? seq.toString() : "";
        String raw = insuredId + "|" + sessionId + "|" + timestamp + "|" + action + "|" + actionValue + "|" + seqStr;
        return sha256(raw);
    }

    /* Computes SHA-256 hex digest of the input */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}