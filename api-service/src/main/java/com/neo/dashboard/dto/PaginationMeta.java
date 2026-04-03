package com.neo.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

/**
 * Pagination metadata copied from Spring Data pages so the frontend can render
 * navigation controls without extra calculations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaginationMeta {
    /* Current page position and total result boundaries. */
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    /* Directional flags used by the client pager. */
    private boolean hasNext;
    private boolean hasPrevious;

    /* Adapt a Spring Data page into the lighter API metadata structure. */
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
