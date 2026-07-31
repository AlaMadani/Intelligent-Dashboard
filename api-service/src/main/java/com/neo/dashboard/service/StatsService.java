package com.neo.dashboard.service;

import com.neo.dashboard.dto.StatsResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Resolves dashboard statistics from Redis first and falls back to an empty
 * payload when nothing is cached.  The action_stats_daily SQL table has been
 * removed — all stats go through Redis.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatsService {

    /** Formatter for compact date keys ({@code yyyyMMdd}), used alongside ISO date keys for Redis cache lookups. */
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** Redis template for reading/writing cached statistics payloads. */
    private final StringRedisTemplate redisTemplate;
    /** Jackson mapper for deserialising cached JSON strings into structured objects. */
    private final ObjectMapper objectMapper;

    /**
     * Retrieves the latest live-statistics snapshot from Redis.  Tries both ISO
     * and compact date key formats.  Returns an empty payload when nothing is
     * cached.
     *
     * @param date the target date; defaults to today when {@code null}
     * @return a {@link StatsResponseDto} containing the cached payload or a missing marker
     */
    @Transactional(readOnly = true)
    public StatsResponseDto getLiveStats(LocalDate date) {
        LocalDate resolvedDate = date == null ? LocalDate.now() : date;
        /* Try each key format (ISO date + compact date) until one hits. */
        for (String key : dateKeys("stats:live:", resolvedDate)) {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                try {
                    JsonNode payload = objectMapper.readTree(cached);
                    return new StatsResponseDto(resolvedDate, "redis", payload);
                } catch (Exception e) {
                    log.warn("Failed to parse cached live stats for key {}", key, e);
                }
            }
        }

        return buildMissing(resolvedDate);
    }

    /**
     * Retrieves trend statistics from Redis.  Checks for a V3.6.1 forecast
     * snapshot first; if absent falls back to the generic trend key.  Returns
     * an empty payload when nothing is cached.
     *
     * @param date the target date; defaults to today when {@code null}
     * @return a {@link StatsResponseDto} with the trend payload or a missing marker
     */
    @Transactional(readOnly = true)
    public StatsResponseDto getTrendStats(LocalDate date) {
        LocalDate resolvedDate = date == null ? LocalDate.now() : date;
        /* Try the dedicated forecast snapshot first (V3.6.1 schema). */
        StatsResponseDto forecastSnapshot = getForecastSnapshot(resolvedDate);
        if (forecastSnapshot != null) {
            return forecastSnapshot;
        }
        /* Fall back to the generic trend-stats cache keys. */
        for (String key : dateKeys("stats:trend:", resolvedDate)) {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                try {
                    JsonNode payload = objectMapper.readTree(cached);
                    return new StatsResponseDto(resolvedDate, "redis", payload);
                } catch (Exception e) {
                    log.warn("Failed to parse cached trend stats for key {}", key, e);
                }
            }
        }

        return buildMissing(resolvedDate);
    }

    /**
     * Attempts to load a forecast snapshot, preferring the dedicated V3.6.1
     * Redis key and falling back to the generic forecast-series / forecasts
     * dashboard keys.
     *
     * @param date the target date to attach to the response
     * @return a {@link StatsResponseDto} if any key holds forecast content, or {@code null}
     */
    private StatsResponseDto getForecastSnapshot(LocalDate date) {
        /* Try the dedicated V3.6.1 forecast cache key first. */
        String v36Forecast = redisTemplate.opsForValue().get(com.neo.dashboard.redis.CacheKeys.DASHBOARD_FORECAST_V36);
        if (v36Forecast != null && !v36Forecast.isBlank()) {
            try {
                return new StatsResponseDto(date, "redis-v3.6.1", objectMapper.readTree(v36Forecast));
            } catch (Exception e) {
                log.warn("Failed to parse V3.6.1 forecast snapshot", e);
            }
        }
        /* Fall back to the legacy forecast dashboard keys. */
        for (String view : Arrays.asList("forecast-series", "forecasts")) {
            String cached = redisTemplate.opsForValue().get(com.neo.dashboard.redis.CacheKeys.dashboardKey(view));
            if (cached == null || cached.isBlank()) {
                continue;
            }
            try {
                JsonNode payload = objectMapper.readTree(cached);
                if (hasForecastContent(payload)) {
                    return new StatsResponseDto(date, "redis", payload);
                }
            } catch (Exception e) {
                log.warn("Failed to parse forecast snapshot view={}", view, e);
            }
        }
        return null;
    }

    /**
     * Builds a consistent "empty" response so callers never receive {@code null}
     * when no cached data is available.
     *
     * @param date the target date
     * @return a {@link StatsResponseDto} with source set to {@code "missing"}
     */
    private StatsResponseDto buildMissing(LocalDate date) {
        JsonNode payload = objectMapper.createObjectNode();
        return new StatsResponseDto(date, "missing", payload);
    }

    /**
     * Checks whether a parsed JSON payload contains actual forecast content
     * (non-empty items, total_events points, or wrapped points).
     *
     * @param payload the parsed JSON node
     * @return {@code true} if the payload holds meaningful forecast data
     */
    private boolean hasForecastContent(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return false;
        }
        /* Check for structured items object. */
        JsonNode items = payload.path("items");
        if (items.isObject() && items.size() > 0) {
            return true;
        }
        /* Check for flat total_events.points array. */
        JsonNode direct = payload.path("total_events").path("points");
        if (direct.isArray() && !direct.isEmpty()) {
            return true;
        }
        /* Check for wrapped items.total_events.points array. */
        JsonNode wrapped = payload.path("items").path("total_events").path("points");
        return wrapped.isArray() && !wrapped.isEmpty();
    }

    /**
     * Generates the list of Redis cache keys to try for a given date, covering
     * both the ISO date format and the compact {@code yyyyMMdd} format, with
     * duplicates removed.
     *
     * @param prefix the Redis key prefix (e.g. {@code "stats:live:"})
     * @param date   the target date
     * @return a deduplicated list of candidate keys
     */
    private List<String> dateKeys(String prefix, LocalDate date) {
        List<String> keys = new ArrayList<>(2);
        keys.add(prefix + date);
        keys.add(prefix + COMPACT_DATE.format(date));
        return keys.stream().filter(Objects::nonNull).distinct().toList();
    }
}
