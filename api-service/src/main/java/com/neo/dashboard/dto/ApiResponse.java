package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard API envelope so every endpoint returns data in a consistent shape
 * and can optionally attach pagination metadata.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    /* Business payload plus optional list metadata. */
    private T data;
    private PaginationMeta meta;

    /* Convenience factory for non-paginated responses. */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null);
    }

    /* Convenience factory for paginated list responses. */
    public static <T> ApiResponse<T> of(T data, PaginationMeta meta) {
        return new ApiResponse<>(data, meta);
    }
}
