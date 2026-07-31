package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Response DTO for the "final winners" (top-anomaly / highest-risk) report.
 * <p>
 * Indicates whether the report is {@link #available}, which resource it
 * was generated from, and carries the report {@link #payload} plus any
 * generation warnings.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36FinalWinnersResponse {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** Whether the final-winners report is available (may be false if the resource is missing). */
    private boolean available;
    /** Resource path or identifier the report was generated from. */
    private String resource;
    /** Instant when the report was generated. */
    private Instant generatedAt;
    /** Warning messages produced during report generation. */
    private List<String> warnings;
    /** The actual report payload (structure depends on the resource type). */
    private Object payload;

    /**
     * Factory method returning an "unavailable" response when the final
     * winners report cannot be found on the classpath.
     *
     * @param resource the resource path that was unavailable
     * @return a response with {@code available = false} and a descriptive warning
     */
    public static V36FinalWinnersResponse unavailable(String resource) {
        /* Build a response indicating the report is not available */
        return new V36FinalWinnersResponse(
                CacheKeys.V36_SCHEMA_VERSION,
                false,
                resource,
                Instant.now(),
                List.of("Final winners report is not available on the api-service classpath"),
                null
        );
    }
}
