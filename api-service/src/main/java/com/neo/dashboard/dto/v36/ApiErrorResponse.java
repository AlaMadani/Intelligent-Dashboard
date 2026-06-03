package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiErrorResponse {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private Instant timestamp;
    private String path;
    private int status;
    private String error;
    private String message;

    public static ApiErrorResponse of(String path, int status, String error, String message) {
        return new ApiErrorResponse(CacheKeys.V36_SCHEMA_VERSION, Instant.now(), path, status, error, message);
    }
}
