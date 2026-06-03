package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.neo.dashboard.redis.CacheKeys;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36FinalWinnersResponse {
    private String schemaVersion = CacheKeys.V36_SCHEMA_VERSION;
    private boolean available;
    private String resource;
    private Instant generatedAt;
    private List<String> warnings;
    private Object payload;

    public static V36FinalWinnersResponse unavailable(String resource) {
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
