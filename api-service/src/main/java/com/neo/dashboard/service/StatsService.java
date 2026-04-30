package com.neo.dashboard.service;

import com.neo.dashboard.dto.ActionStatsDailyDto;
import com.neo.dashboard.dto.StatsResponseDto;
import com.neo.dashboard.mapper.ActionStatsDailyMapper;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.ActionStatsDailyRepository;
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
import java.util.stream.Collectors;

/**
 * Resolves dashboard statistics from Redis first and falls back to SQL-backed
 * daily aggregates when needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatsService {

    /* Redis may use either ISO dates or compact yyyyMMdd dates in cache keys. */
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /* Cache access, JSON conversion, and SQL fallback repository. */
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ActionStatsDailyRepository actionStatsDailyRepository;
    private final ActionStatsDailyMapper actionStatsDailyMapper;

    /*
     * Read the latest live snapshot from Redis and return an empty payload if
     * nothing is cached.  No @Transactional here: the method only touches Redis,
     * so acquiring a JDBC connection from HikariCP would be wasteful.
     */
    @Transactional(readOnly = true)
    public StatsResponseDto getLiveStats(LocalDate date) {
        LocalDate resolvedDate = date == null ? LocalDate.now() : date;
        for (String key : dateKeys("stats:live:", resolvedDate)) {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                try {
                    // Cached stats are stored as generic JSON because the shape can evolve over time.
                    JsonNode payload = objectMapper.readTree(cached);
                    return new StatsResponseDto(resolvedDate, "redis", payload);
                } catch (Exception e) {
                    log.warn("Failed to parse cached live stats for key {}", key, e);
                }
            }
        }

        return buildMissing(resolvedDate);
    }

    /*
     * Read trend stats from Redis and fall back to SQL aggregates when Redis is
     * empty.  The Redis path avoids opening a transaction; the SQL fallback is
     * isolated in its own @Transactional method so a JDBC connection is only
     * acquired when truly needed.
     */
    @Transactional(readOnly = true)
    public StatsResponseDto getTrendStats(LocalDate date) {
        LocalDate resolvedDate = date == null ? LocalDate.now() : date;
        StatsResponseDto forecastSnapshot = getForecastSnapshot(resolvedDate);
        if (forecastSnapshot != null) {
            return forecastSnapshot;
        }
        for (String key : dateKeys("stats:trend:", resolvedDate)) {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                try {
                    // Trend payloads follow the same generic JSON contract as live stats.
                    JsonNode payload = objectMapper.readTree(cached);
                    return new StatsResponseDto(resolvedDate, "redis", payload);
                } catch (Exception e) {
                    log.warn("Failed to parse cached trend stats for key {}", key, e);
                }
            }
        }

        return buildFromSql(resolvedDate, "sql");
    }

    private StatsResponseDto getForecastSnapshot(LocalDate date) {
        for (String view : Arrays.asList("forecast-series", "forecasts")) {
            String cached = redisTemplate.opsForValue().get(CacheKeys.dashboardKey(view));
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

    /* Convert SQL rows into the generic JSON payload expected by the API.
     * This is the only path that actually needs a database connection. */
    @Transactional(readOnly = true)
    StatsResponseDto buildFromSql(LocalDate date, String source) {
        List<ActionStatsDailyDto> dtos = actionStatsDailyRepository.findByStatDate(date).stream()
                .map(actionStatsDailyMapper::toDto)
                .collect(Collectors.toList());
        JsonNode payload = objectMapper.valueToTree(dtos);
        return new StatsResponseDto(date, source, payload);
    }

    /* Return a consistent empty payload instead of null when live cache data is absent. */
    private StatsResponseDto buildMissing(LocalDate date) {
        JsonNode payload = objectMapper.createObjectNode();
        return new StatsResponseDto(date, "missing", payload);
    }

    private boolean hasForecastContent(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return false;
        }
        JsonNode items = payload.path("items");
        if (items.isObject() && items.size() > 0) {
            return true;
        }
        JsonNode direct = payload.path("total_events").path("points");
        if (direct.isArray() && !direct.isEmpty()) {
            return true;
        }
        JsonNode wrapped = payload.path("items").path("total_events").path("points");
        return wrapped.isArray() && !wrapped.isEmpty();
    }

    /* Support both supported key formats while avoiding duplicates. */
    private List<String> dateKeys(String prefix, LocalDate date) {
        List<String> keys = new ArrayList<>(2);
        keys.add(prefix + date);
        keys.add(prefix + COMPACT_DATE.format(date));
        return keys.stream().filter(Objects::nonNull).distinct().toList();
    }
}
