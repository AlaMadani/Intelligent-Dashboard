package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Full system diagnostics response for the V36 schema.
 * <p>
 * Aggregates runtime health, field-coverage statistics, model-latency
 * information, fallback-mode status, and internal subsystem snapshots
 * (Kafka, idempotency, performance, session finalization).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36DiagnosticsResponse {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** Per-model runtime health summary. */
    private V36RuntimeHealthResponse runtimeHealth;
    /** Feature-level field coverage statistics. */
    private Map<String, Object> fieldCoverage;
    /** Latency measurements for each model (may be a map or structured object). */
    private Object modelLatency;
    /** Current fallback-mode identifier (e.g. "none", "partial", "full"). */
    private String fallbackMode;
    /** List of diagnostic warning messages. */
    private List<String> warnings;
    /** Kafka consumer/producer health and metrics snapshot. */
    private Map<String, Object> kafka;
    /** Idempotency-store health and metrics snapshot. */
    private Map<String, Object> idempotency;
    /** Performance counters and throughput metrics. */
    private Map<String, Object> performance;
    /** General statistics counters. */
    private Map<String, Object> stats;
    /** Session-finalization diagnostic information. */
    private Map<String, Object> sessionFinalization;
}
