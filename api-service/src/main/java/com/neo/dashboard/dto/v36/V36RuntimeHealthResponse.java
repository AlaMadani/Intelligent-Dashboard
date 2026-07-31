package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for the overall runtime health of the AI models.
 * <p>
 * Aggregates version information, feature flags (persona, LLM),
 * fallback mode, per-model health states, and subsystem snapshots
 * (Kafka, idempotency, performance, session finalization).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36RuntimeHealthResponse {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** Version identifier of the deployed runtime. */
    private String runtimeVersion;
    /** Base path where model artifacts are stored on disk. */
    private String artifactBasePath;
    /** Whether persona detection is enabled. */
    private Boolean personaEnabled;
    /** Whether LLM explanation runs within the data-processor pipeline. */
    private Boolean llmExplanationInDataprocessor;
    /** Whether LLM evidence payload generation is enabled. */
    private Boolean llmEvidencePayloadEnabled;
    /** Current fallback-mode identifier (e.g. "none", "partial", "full"). */
    private String fallbackMode;
    /** Overall health status (e.g. "HEALTHY", "DEGRADED", "UNKNOWN"). */
    private String status;
    /** Human-readable status message. */
    private String message;
    /** Per-model runtime health states keyed by model name. */
    private Map<String, V36ModelRuntimeStateDto> modelHealth;
    /** List of health-check warning messages. */
    private List<String> warnings;
    /** Session-finalization diagnostic information. */
    private Map<String, Object> sessionFinalization;
    /** Kafka consumer/producer health and metrics snapshot. */
    private Map<String, Object> kafka;
    /** Idempotency-store health and metrics snapshot. */
    private Map<String, Object> idempotency;
    /** Performance counters and throughput metrics. */
    private Map<String, Object> performance;
    /** Latency measurements for each model. */
    private Map<String, Object> modelLatency;
    /** General statistics counters. */
    private Map<String, Object> stats;
    /** Next-action prediction subsystem health and metrics. */
    private Map<String, Object> nextActionPrediction;
    /** Source system or component that produced this health response. */
    private String source;

    /**
     * Sets the {@link #sessionFinalization} map from a diagnostics
     * snapshot, but only if the snapshot is non-null and non-empty.
     *
     * @param diag the session-finalization diagnostics data
     */
    public void ensureSessionFinalization(Map<String, Object> diag) {
        /* Only overwrite with non-empty diagnostic data */
        if (diag != null && !diag.isEmpty()) {
            this.sessionFinalization = diag;
        }
    }

    /**
     * Factory method returning a minimal "unknown" health response
     * when the runtime health snapshot cannot be retrieved from Redis.
     *
     * @return a response with {@code status = "UNKNOWN"} and a descriptive warning
     */
    public static V36RuntimeHealthResponse unknown() {
        /* Build a response indicating the health snapshot is missing */
        V36RuntimeHealthResponse response = new V36RuntimeHealthResponse();
        response.setRuntimeVersion(CacheKeys.V36_SCHEMA_VERSION);
        response.setStatus("UNKNOWN");
        response.setMessage("Runtime health snapshot not available");
        response.setPersonaEnabled(false);
        response.setLlmExplanationInDataprocessor(false);
        response.setLlmEvidencePayloadEnabled(false);
        response.setModelHealth(Map.of());
        response.setWarnings(List.of("Redis key " + CacheKeys.AI_RUNTIME_HEALTH_V36 + " is missing"));
        return response;
    }
}
