package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiPageResponse<T> {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private List<T> items;
    private Integer limit;
    private Integer offset;
    private Integer count;
    private Boolean hasMore;

    public static <T> ApiPageResponse<T> of(List<T> items, Integer limit, Integer offset, boolean hasMore) {
        List<T> safeItems = items == null ? List.of() : items;
        return new ApiPageResponse<>(
                CacheKeys.V36_SCHEMA_VERSION,
                safeItems,
                limit,
                offset,
                safeItems.size(),
                hasMore
        );
    }
}
