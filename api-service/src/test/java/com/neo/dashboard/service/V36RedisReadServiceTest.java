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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Read Json Returns Payload When Redis Value Contains Json
     */
    @Test
    void readJsonReturnsPayloadWhenRedisValueContainsJson() {
        when(valueOperations.get("ai:runtime:health:v3_6")).thenReturn("{\"schemaVersion\":\"v3.6.1\",\"status\":\"HEALTHY\"}");

        Optional<JsonNode> result = service.readJson("ai:runtime:health:v3_6");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().path("status").asText()).isEqualTo("HEALTHY");
    }

    /**
     * Read Json Returns Empty For Malformed Json
     */
    @Test
    void readJsonReturnsEmptyForMalformedJson() {
        when(valueOperations.get("bad")).thenReturn("{not-json");

        Optional<JsonNode> result = service.readJson("bad");

        assertThat(result).isEmpty();
    }

    /**
     * Read Items Handles Duplicate List Entries
     */
    @Test
    void readItemsHandlesDuplicateListEntries() {
        when(listOperations.range("alerts:live:v3_6", 0, 4)).thenReturn(List.of(
                "{\"eventId\":\"evt-1\"}",
                "{\"eventId\":\"evt-2\"}",
                "{\"eventId\":\"evt-1\"}",
                "{\"eventId\":\"evt-3\"}",
                "{\"eventId\":\"evt-2\"}"
        ));

        List<V36LiveAlertSummaryDto> result = service.readItems("alerts:live:v3_6", V36LiveAlertSummaryDto.class, 5);

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(V36LiveAlertSummaryDto::getEventId).toList())
                .containsExactly("evt-1", "evt-2", "evt-3");
    }

    /**
     * Read Items Supports Redis List Payloads
     */
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

    /**
     * Read Z Set Alert Items Returns Deserialized Payloads
     */
    @Test
    void readZSetAlertItemsReturnsDeserializedPayloads() {
        Set<String> zsetMembers = new LinkedHashSet<>();
        zsetMembers.add("evt-1");
        zsetMembers.add("evt-2");
        when(zSetOperations.reverseRange("alerts:live:zset:v3_6", 0, 4))
                .thenReturn(zsetMembers);
        when(valueOperations.get("alert:live:v3_6:evt-1"))
                .thenReturn("{\"eventId\":\"evt-1\",\"riskLevel\":\"CRITICAL\"}");
        when(valueOperations.get("alert:live:v3_6:evt-2"))
                .thenReturn("{\"eventId\":\"evt-2\",\"riskLevel\":\"HIGH\"}");

        List<V36LiveAlertSummaryDto> result = service.readZSetAlertItems(
                "alerts:live:zset:v3_6", "alert:live:v3_6:", V36LiveAlertSummaryDto.class, 5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEventId()).isEqualTo("evt-1");
        assertThat(result.get(1).getEventId()).isEqualTo("evt-2");
    }

    /**
     * Read Z Set Alert Items Handles Wron Type Gracefully
     */
    @Test
    void readZSetAlertItemsHandlesWronTypeGracefully() {
        when(zSetOperations.reverseRange("alerts:live:zset:v3_6", 0, 4))
                .thenThrow(new RuntimeException("WRONGTYPE Operation against a key holding the wrong kind of value"));

        List<V36LiveAlertSummaryDto> result = service.readZSetAlertItems(
                "alerts:live:zset:v3_6", "alert:live:v3_6:", V36LiveAlertSummaryDto.class, 5);

        assertThat(result).isEmpty();
    }

    /**
     * Read Z Set Alert Items Skips Members With Missing Payloads
     */
    @Test
    void readZSetAlertItemsSkipsMembersWithMissingPayloads() {
        Set<String> zsetMembers = new LinkedHashSet<>();
        zsetMembers.add("evt-1");
        zsetMembers.add("evt-2");
        when(zSetOperations.reverseRange("alerts:live:zset:v3_6", 0, 4))
                .thenReturn(zsetMembers);
        when(valueOperations.get("alert:live:v3_6:evt-1"))
                .thenReturn("{\"eventId\":\"evt-1\",\"riskLevel\":\"CRITICAL\"}");
        when(valueOperations.get("alert:live:v3_6:evt-2"))
                .thenReturn(null);

        List<V36LiveAlertSummaryDto> result = service.readZSetAlertItems(
                "alerts:live:zset:v3_6", "alert:live:v3_6:", V36LiveAlertSummaryDto.class, 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo("evt-1");
    }
}
