package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard API response wrapper that enforces a consistent envelope shape
 * across all endpoints. Carries the business payload and, for paginated
 * results, the associated pagination metadata.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    /* The actual response payload (could be a single object, a list, a map, etc.). */
     /* Optional pagination info present only when the response is a pageable list. */
    private T data;
    private PaginationMeta meta;

    /**
     * Creates a response with data but no pagination metadata.
     * Use this for single-object or non-listing endpoints.
     */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null);
    }

    /**
     * Creates a response with data and the attached pagination metadata.
     * Use this for endpoints that return paged lists.
     */
    public static <T> ApiResponse<T> of(T data, PaginationMeta meta) {
        return new ApiResponse<>(data, meta);
    }
}
