package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36LiveAlertSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V36RedisReadServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);
    private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private V36RedisReadService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        service = new V36RedisReadService(redisTemplate, objectMapper);
    }

    @Test
    void readJsonReturnsPayloadWhenRedisValueContainsJson() {
        when(valueOperations.get("ai:runtime:health:v3_6")).thenReturn("{\"schemaVersion\":\"v3.6.1\",\"status\":\"HEALTHY\"}");

        Optional<JsonNode> result = service.readJson("ai:runtime:health:v3_6");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().path("status").asText()).isEqualTo("HEALTHY");
    }

    @Test
    void readJsonReturnsEmptyForMalformedJson() {
        when(valueOperations.get("bad")).thenReturn("{not-json");

        Optional<JsonNode> result = service.readJson("bad");

        assertThat(result).isEmpty();
    }

    @Test
    void readItemsSupportsRedisListPayloads() {
        when(listOperations.range("alerts:live:v3_6", 0, 1)).thenReturn(List.of(
                "{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-1\",\"riskLevel\":\"CRITICAL\"}",
                "{\"schemaVersion\":\"v3.6.1\",\"eventId\":\"evt-2\",\"riskLevel\":\"HIGH\"}"
        ));

        List<V36LiveAlertSummaryDto> result = service.readItems("alerts:live:v3_6", V36LiveAlertSummaryDto.class, 2);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().getEventId()).isEqualTo("evt-1");
    }
}
