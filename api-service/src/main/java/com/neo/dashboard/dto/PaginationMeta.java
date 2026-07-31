package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

/**
 * Lightweight pagination metadata extracted from Spring Data {@link Page}
 * so the frontend can render navigation controls without performing any
 * client-side calculations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaginationMeta {
    /* Zero-based index of the current page. */
    private int page;
    /* Maximum number of items per page. */
    private int size;
    /* Total number of items across all pages. */
    private long totalElements;
    /* Total number of pages based on size and totalElements. */
    private int totalPages;

    /* Whether a subsequent page exists (next-button enabled flag). */
    private boolean hasNext;
    /* Whether a preceding page exists (previous-button enabled flag). */
    private boolean hasPrevious;

    /**
     * Converts a Spring Data {@link Page} into this lightweight transport DTO.
     * Call this before serialising paginated endpoint responses.
     */
    public static PaginationMeta fromPage(Page<?> page) {
        return new PaginationMeta(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious()
        );
    }
}
