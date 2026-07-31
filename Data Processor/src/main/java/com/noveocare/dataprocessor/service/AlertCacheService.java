package com.noveocare.dataprocessor.service;

import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.dto.V36LiveAlertSummary;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical alert cache enforcing {@code critical ZSET ⊆ live ZSET}.
 *
 * <p>Every write goes through {@link #addAlert(V36LiveAlertSummary)} which writes
 * to the live ZSET first. If the live write fails, derived ZSETs are skipped.
 * CRITICAL / HIGH alerts are also added to their respective derived ZSETs.</p>
 *
 * <p>Members are always {@code eventId} (string). Score is the event's
 * {@code createdAt} epoch millis, with fallback to {@code timestamp} or
 * {@code System.currentTimeMillis()}.</p>
 *
 * <p>Payloads are stored separately under {@code alert:live:v3_6:{eventId}}
 * as a JSON-encoded {@link V36LiveAlertSummary}.</p>
 *
 * <p>See {@link CacheKeys} for the full Redis key contract
 * (canonical keys, legacy keys, TTL, trimming, invariants).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertCacheService {

    /* Redis key constants and cache size limits */
    static final String CANONICAL_LIVE_ZSET = CacheKeys.liveAlertsV36ZSetKey();
    static final int MAX_LIVE_ALERTS = 5000;

    /* Injected dependencies */
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties redisCacheProperties;
    private final StringRedisTemplate redisTemplate;

    /* --- Alert CRUD operations --- */

    public void addAlert(V36LiveAlertSummary summary) {
        String eventId = summary.getEventId();
        if (eventId == null || eventId.isBlank()) {
            return;
        }

        long score = epochMillis(summary.getCreatedAt(), summary.getTimestamp());
        String insuredId = summary.getInsuredId();

        redisCacheService.zsetAdd(CANONICAL_LIVE_ZSET, eventId, score);
        redisTemplate.expire(CANONICAL_LIVE_ZSET, redisCacheProperties.getLiveStats());
        redisCacheService.setJson(CacheKeys.liveAlertsV36PayloadKey(eventId), summary, redisCacheProperties.getLiveStats());

        if ("CRITICAL".equalsIgnoreCase(summary.getRiskLevel())) {
            redisCacheService.zsetAdd(CacheKeys.criticalAlertsV36ZSetKey(), eventId, score);
            redisTemplate.expire(CacheKeys.criticalAlertsV36ZSetKey(), redisCacheProperties.getLiveStats());
        }
        if ("HIGH".equalsIgnoreCase(summary.getRiskLevel())) {
            redisCacheService.zsetAdd(CacheKeys.highAlertsV36ZSetKey(), eventId, score);
            redisTemplate.expire(CacheKeys.highAlertsV36ZSetKey(), redisCacheProperties.getLiveStats());
        }

        if (insuredId != null && !insuredId.isBlank()) {
            redisCacheService.zsetAdd(CacheKeys.userAlertsV36ZSetKey(insuredId), eventId, score);
            redisTemplate.expire(CacheKeys.userAlertsV36ZSetKey(insuredId), redisCacheProperties.getLiveStats());
        }

        trimToMaxSize();
    }

    /* Removes an alert from all related ZSETs */
    public void removeAlert(String eventId, String insuredId, String riskLevel) {
        if (eventId == null || eventId.isBlank()) return;
        redisCacheService.zsetRemove(CANONICAL_LIVE_ZSET, eventId);
        redisCacheService.zsetRemove(CacheKeys.criticalAlertsV36ZSetKey(), eventId);
        redisCacheService.zsetRemove(CacheKeys.highAlertsV36ZSetKey(), eventId);
        if (insuredId != null && !insuredId.isBlank()) {
            redisCacheService.zsetRemove(CacheKeys.userAlertsV36ZSetKey(insuredId), eventId);
        }
    }

    /* --- Query methods --- */

    public List<String> getLiveEventIds(int offset, int limit) {
        Set<String> members = redisCacheService.zsetReverseRange(CANONICAL_LIVE_ZSET, offset, offset + limit - 1L);
        return new ArrayList<>(members);
    }

    public List<String> getCriticalEventIds(int offset, int limit) {
        Set<String> members = redisCacheService.zsetReverseRange(CacheKeys.criticalAlertsV36ZSetKey(), offset, offset + limit - 1L);
        return new ArrayList<>(members);
    }

    public V36LiveAlertSummary getAlertPayload(String eventId) {
        return redisCacheService.getJson(CacheKeys.liveAlertsV36PayloadKey(eventId), V36LiveAlertSummary.class);
    }

    public long liveCount() {
        return redisCacheService.zsetCard(CANONICAL_LIVE_ZSET);
    }

    public long criticalCount() {
        return redisCacheService.zsetCard(CacheKeys.criticalAlertsV36ZSetKey());
    }

    /* --- Internal maintenance --- */

    private void trimToMaxSize() {
        long liveSize = redisCacheService.zsetCard(CANONICAL_LIVE_ZSET);
        if (liveSize <= MAX_LIVE_ALERTS) {
            return;
        }
        long removeCount = liveSize - (MAX_LIVE_ALERTS / 2L);
        Set<String> oldest = redisCacheService.zsetRange(CANONICAL_LIVE_ZSET, 0, removeCount - 1);
        if (oldest.isEmpty()) {
            return;
        }
        String[] toRemove = oldest.toArray(new String[0]);
        redisCacheService.zsetRemove(CANONICAL_LIVE_ZSET, (Object[]) toRemove);
        redisCacheService.zsetRemove(CacheKeys.criticalAlertsV36ZSetKey(), (Object[]) toRemove);
        redisCacheService.zsetRemove(CacheKeys.highAlertsV36ZSetKey(), (Object[]) toRemove);
        for (String member : toRemove) {
            redisCacheService.zsetRemove(CacheKeys.liveAlertsV36PayloadKey(member));
        }
    }

    /* Diagnostics and consistency checks */
    public Map<String, Object> consistencyDiagnostics() {
        Map<String, Object> diag = new LinkedHashMap<>();
        try {
            long live = liveCount();
            long critical = criticalCount();

            List<String> missingFromLive = new ArrayList<>();
            if (critical > 0) {
                Set<String> criticalIds = redisCacheService.zsetReverseRange(CacheKeys.criticalAlertsV36ZSetKey(), 0, (int) critical - 1);
                for (String id : criticalIds) {
                    Double score = redisCacheService.zsetScore(CANONICAL_LIVE_ZSET, id);
                    if (score == null) {
                        missingFromLive.add(id);
                    }
                }
            }

            diag.put("liveCount", live);
            diag.put("criticalCount", critical);
            diag.put("criticalMissingFromLiveCount", (long) missingFromLive.size());
            diag.put("lastMissingCriticalEventIds", missingFromLive);
        } catch (Exception e) {
            log.warn("Alert cache consistency check failed", e);
            diag.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        diag.put("lastConsistencyCheckAt", Instant.now().toString());
        return diag;
    }

    /* Resolves the best available epoch millis for scoring */
    private long epochMillis(Instant createdAt, String timestamp) {
        if (createdAt != null) {
            return createdAt.toEpochMilli();
        }
        if (timestamp != null) {
            try {
                return Instant.parse(timestamp).toEpochMilli();
            } catch (Exception e) {
                // fall through
            }
        }
        return System.currentTimeMillis();
    }
}
