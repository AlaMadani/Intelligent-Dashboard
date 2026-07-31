package com.noveocare.dataprocessor.ai.sequence;

import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors coverage of sequence model fields (known vs unknown categoricals,
 * missing continuous values) and publishes live stats to Redis.
 */
@Service
@RequiredArgsConstructor
public class SequenceFieldCoverageMonitor {

    /* Fields that trigger a warning if unknown-rate exceeds 30%. */
    private static final Set<String> CRITICAL_FIELDS = Set.of(
            "frontend_action_name", "api_template", "action_value", "action_type", "action_subtype",
            "status", "ip_country", "controller", "api_family");

    /* ---- Dependencies ---- */
    private final RuntimeArtifactService artifactService;
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;

    /* ---- Live counters ---- */
    private final AtomicLong totalEvents = new AtomicLong();
    private final Map<String, CategoricalStats> categoricalStats = new ConcurrentHashMap<>();
    private final Map<String, ContinuousStats> continuousStats = new ConcurrentHashMap<>();

    /* ========== Public API ========== */

    /* Records coverage data for a single encoded event and publishes to Redis. */
    public void record(SequenceEventValues values, EncodedSequenceEvent encoded) {
        totalEvents.incrementAndGet();
        List<String> catCols = artifactService.getSequenceMetadata().getCatCols();
        for (int i = 0; i < catCols.size(); i++) {
            String field = catCols.get(i);
            long encodedId = encoded.getCategoricalIds() == null || i >= encoded.getCategoricalIds().length
                    ? 0L
                    : encoded.getCategoricalIds()[i];
            String raw = values.getCategoricalValues().get(field);
            CategoricalStats stats = categoricalStats.computeIfAbsent(field, ignored -> new CategoricalStats());
            stats.total.incrementAndGet();
            if (encodedId > 0) {
                stats.known.incrementAndGet();
            } else {
                stats.unknown.incrementAndGet();
                if (raw != null && !raw.isBlank()) {
                    stats.unknownValues.computeIfAbsent(raw, ignored -> new AtomicLong()).incrementAndGet();
                }
            }
        }

        for (String field : artifactService.getSequenceMetadata().getContCols()) {
            ContinuousStats stats = continuousStats.computeIfAbsent(field, ignored -> new ContinuousStats());
            stats.total.incrementAndGet();
            if (values.getWarnings().contains("missing_required_field_" + field)) {
                stats.missing.incrementAndGet();
                stats.defaultUsed.incrementAndGet();
            }
            if (values.getWarnings().contains("legacy_fallback_" + field)) {
                stats.fallbackUsed.incrementAndGet();
            }
        }
        redisCacheService.setJson(CacheKeys.sequenceFieldCoverageKey(), snapshot(), cacheProperties.getLiveStats());
    }

    /* Returns a snapshot of coverage stats for external monitoring. */
    public Map<String, Object> snapshot() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalEvents", totalEvents.get());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : artifactService.getSequenceMetadata().getCatCols()) {
            CategoricalStats stats = categoricalStats.getOrDefault(field, new CategoricalStats());
            long total = stats.total.get();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("known", stats.known.get());
            item.put("unknown", stats.unknown.get());
            item.put("unknownRatio", total == 0L ? 0.0 : (double) stats.unknown.get() / total);
            item.put("topUnknownRawValues", topUnknown(stats.unknownValues));
            fields.put(field, item);
        }
        for (String field : artifactService.getSequenceMetadata().getContCols()) {
            ContinuousStats stats = continuousStats.getOrDefault(field, new ContinuousStats());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("total", stats.total.get());
            item.put("missing", stats.missing.get());
            item.put("invalid", stats.invalid.get());
            item.put("fallbackUsed", stats.fallbackUsed.get());
            item.put("defaultUsed", stats.defaultUsed.get());
            fields.put(field, item);
        }
        payload.put("fields", fields);
        payload.put("highUnknownFieldWarnings", highUnknownWarnings());
        return payload;
    }

    /* Returns warnings for critical fields with unknown-rate > 30%. */
    public List<String> highUnknownWarnings() {
        return CRITICAL_FIELDS.stream()
                .filter(field -> {
                    CategoricalStats stats = categoricalStats.get(field);
                    if (stats == null || stats.total.get() == 0L) {
                        return false;
                    }
                    return (double) stats.unknown.get() / stats.total.get() > 0.30;
                })
                .map(field -> "high_unknown_rate_" + field)
                .toList();
    }

    /* ========== Private helpers ========== */

    /* Returns the top 5 unknown raw values by frequency. */
    private Map<String, Long> topUnknown(Map<String, AtomicLong> values) {
        Map<String, Long> result = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, AtomicLong>>comparingLong(entry -> entry.getValue().get()).reversed())
                .limit(5)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().get()));
        return result;
    }

    /* Per-field categorical coverage counters. */
    private static final class CategoricalStats {
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong known = new AtomicLong();
        private final AtomicLong unknown = new AtomicLong();
        private final Map<String, AtomicLong> unknownValues = new ConcurrentHashMap<>();
    }

    /* Per-field continuous coverage counters. */
    private static final class ContinuousStats {
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong missing = new AtomicLong();
        private final AtomicLong invalid = new AtomicLong();
        private final AtomicLong fallbackUsed = new AtomicLong();
        private final AtomicLong defaultUsed = new AtomicLong();
    }
}
