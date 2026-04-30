package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardReadServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private DashboardReadService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new DashboardReadService(redisTemplate, objectMapper);
    }

    @Test
    void getSnapshotOrDefaultReturnsEmptyItemsPayloadForListViewsWhenCacheIsEmpty() {
        when(valueOperations.get("dashboard:cluster-mix")).thenReturn(null);

        Optional<JsonNode> result = service.getSnapshotOrDefault("cluster-mix");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().path("items").isArray()).isTrue();
        assertThat(result.orElseThrow().path("items")).isEmpty();
    }

    @Test
    void getSnapshotOrDefaultReturnsEmptyObjectForForecastViewsWhenCacheIsEmpty() {
        when(valueOperations.get("dashboard:forecasts")).thenReturn(null);

        Optional<JsonNode> result = service.getSnapshotOrDefault("forecasts");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().isObject()).isTrue();
        assertThat(result.orElseThrow().size()).isZero();
    }

    @Test
    void getSnapshotOrDefaultRejectsUnsupportedViews() {
        Optional<JsonNode> result = service.getSnapshotOrDefault("unknown-view");

        assertThat(result).isEmpty();
    }
}
