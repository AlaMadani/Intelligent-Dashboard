package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Standard API error response DTO for the V36 schema.
 * <p>
 * Used across all REST endpoints to return a consistent error envelope
 * containing a schema version, timestamp, HTTP path/status, and both a
 * machine-readable error code and a human-readable message.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiErrorResponse {
    /** Schema version identifier for response backward-compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** Instant when the error was generated (server-side). */
    private Instant timestamp;
    /** The HTTP request path that triggered the error. */
    private String path;
    /** The HTTP status code (e.g. 400, 404, 500). */
    private int status;
    /** Short, machine-readable error code / reason phrase (e.g. "Bad Request", "Not Found"). */
    private String error;
    /** Human-readable detail message explaining the error (e.g. validation failures or exception message). */
    private String message;

    /**
     * Convenience factory that sets {@code timestamp} to {@link Instant#now()}
     * and {@code schemaVersion} to {@link CacheKeys#V36_SCHEMA_VERSION}.
     *
     * @param path    the request path that produced the error
     * @param status  the HTTP status code
     * @param error   the error code / reason phrase
     * @param message the human-readable detail
     * @return a fully populated {@code ApiErrorResponse}
     */
    public static ApiErrorResponse of(String path, int status, String error, String message) {
        /* Build and return a new error instance with the current timestamp */
        return new ApiErrorResponse(CacheKeys.V36_SCHEMA_VERSION, Instant.now(), path, status, error, message);
    }
}
