package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Generic paginated API response wrapper for the V36 schema.
 * <p>
 * Carries a typed list of items together with the pagination parameters
 * (limit, offset, total count) and a {@code hasMore} flag so clients
 * can determine whether additional pages exist.
 *
 * @param <T> the type of items in the current page
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiPageResponse<T> {
    /** Schema version for response compatibility. */
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    /** The page of items (never null when served via {@link #of}). */
    private List<T> items;
    /** Maximum number of items requested per page. */
    private Integer limit;
    /** Zero-based offset identifying the start of this page. */
    private Integer offset;
    /** Total item count across <em>all</em> pages (when available), or the size of the current page otherwise. */
    private Integer count;
    /** Flag indicating whether more results exist beyond this page; {@code null} when the total count is unknown. */
    private Boolean hasMore;

    /**
     * Factory for scenarios where the total item count is <em>unknown</em>
     * so that {@code hasMore} is determined from the presence of a next page.
     *
     * @param items   the items for this page (may be null, treated as empty)
     * @param limit   the page size limit
     * @param offset  the zero-based start offset
     * @param hasMore true if additional pages exist
     * @param <T>     item type
     * @return a populated {@code ApiPageResponse}
     */
    public static <T> ApiPageResponse<T> of(List<T> items, Integer limit, Integer offset, boolean hasMore) {
        /* Protect against a null items list by defaulting to an empty list */
        List<T> safeItems = items == null ? List.of() : items;
        /* Use the safe-list size as the count */
        return new ApiPageResponse<>(
                CacheKeys.V36_SCHEMA_VERSION,
                safeItems,
                limit,
                offset,
                safeItems.size(),
                hasMore
        );
    }

    /**
     * Factory for scenarios where the <em>total</em> number of matching
     * records is known, letting clients compute {@code hasMore} themselves.
     *
     * @param items      the items for this page (may be null, treated as empty)
     * @param limit      the page size limit
     * @param offset     the zero-based start offset
     * @param totalCount the total number of records across all pages
     * @param <T>        item type
     * @return a populated {@code ApiPageResponse}
     */
    public static <T> ApiPageResponse<T> of(List<T> items, Integer limit, Integer offset, int totalCount) {
        /* Protect against a null items list */
        List<T> safeItems = items == null ? List.of() : items;
        /* Determine whether there are more results beyond the current page */
        boolean hasMore = offset + safeItems.size() < totalCount;
        return new ApiPageResponse<>(
                CacheKeys.V36_SCHEMA_VERSION,
                safeItems,
                limit,
                offset,
                totalCount,
                hasMore
        );
    }
}
