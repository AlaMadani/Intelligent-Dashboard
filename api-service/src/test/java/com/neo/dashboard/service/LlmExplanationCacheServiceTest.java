package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36LlmExplanationResponse;
import com.neo.dashboard.entity.LlmExplanation;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.LlmExplanationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmExplanationCacheServiceTest {

    private final V36RedisReadService redisReadService = mock(V36RedisReadService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final LlmExplanationRepository explanationRepository = mock(LlmExplanationRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private LlmExplanationCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new LlmExplanationCacheService(
                redisReadService, redisTemplate, explanationRepository, objectMapper);
        ReflectionTestUtils.setField(cacheService, "cacheEnabled", true);
        ReflectionTestUtils.setField(cacheService, "cacheTtlHours", 24L);
    }

    @Test
    void getLatestReturnsRedisHit() {
        V36LlmExplanationResponse redisResponse = new V36LlmExplanationResponse();
        redisResponse.setEventId("evt-1");
        redisResponse.setSummary("cached from redis");
        when(redisReadService.readValue(CacheKeys.explanationV36LatestKey("evt-1"),
                V36LlmExplanationResponse.class)).thenReturn(Optional.of(redisResponse));

        Optional<V36LlmExplanationResponse> result = cacheService.getLatest("evt-1");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getCached()).isTrue();
        assertThat(result.orElseThrow().getSource()).isEqualTo("redis");
        verify(explanationRepository, never()).findTopByEventIdAndCurrentTrueOrderByUpdatedAtDesc(anyString());
    }

    @Test
    void getLatestFallsBackToSqlAndRehydratesRedis() {
        when(redisReadService.readValue(CacheKeys.explanationV36LatestKey("evt-2"),
                V36LlmExplanationResponse.class)).thenReturn(Optional.empty());

        LlmExplanation sqlEntity = new LlmExplanation();
        sqlEntity.setEventId("evt-2");
        sqlEntity.setEvidenceHash("hash2");
        sqlEntity.setStyle("security_analyst");
        sqlEntity.setLanguage("en");
        sqlEntity.setExplanationJson("""
                {"schemaVersion":"v3.6.1","eventId":"evt-2","summary":"from sql","provider":"gemini","model":"gemini-2.5-flash","cached":false,"fallback":false}
                """);
        sqlEntity.setSummary("from sql");
        when(explanationRepository.findTopByEventIdAndCurrentTrueOrderByUpdatedAtDesc("evt-2"))
                .thenReturn(Optional.of(sqlEntity));

        Optional<V36LlmExplanationResponse> result = cacheService.getLatest("evt-2");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getCached()).isTrue();
        assertThat(result.orElseThrow().getSource()).isEqualTo("sql_fallback");
        verify(redisReadService).writeJson(
                eq(CacheKeys.explanationV36Key("evt-2", "hash2", "security_analyst", "en")),
                any(), any(Duration.class));
        verify(redisReadService).writeJson(
                eq(CacheKeys.explanationV36LatestKey("evt-2")),
                any(), any(Duration.class));
    }

    @Test
    void getLatestBothMissReturnsEmpty() {
        when(redisReadService.readValue(CacheKeys.explanationV36LatestKey("evt-3"),
                V36LlmExplanationResponse.class)).thenReturn(Optional.empty());
        when(explanationRepository.findTopByEventIdAndCurrentTrueOrderByUpdatedAtDesc("evt-3"))
                .thenReturn(Optional.empty());

        Optional<V36LlmExplanationResponse> result = cacheService.getLatest("evt-3");

        assertThat(result).isEmpty();
    }

    @Test
    void getReturnsRedisHit() {
        V36LlmExplanationResponse redisResponse = new V36LlmExplanationResponse();
        redisResponse.setEventId("evt-4");
        redisResponse.setSummary("specific cached");
        when(redisReadService.readValue(
                CacheKeys.explanationV36Key("evt-4", "hash4", "security_analyst", "en"),
                V36LlmExplanationResponse.class)).thenReturn(Optional.of(redisResponse));

        Optional<V36LlmExplanationResponse> result = cacheService.get("evt-4", "hash4", "security_analyst", "en");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getCached()).isTrue();
        assertThat(result.orElseThrow().getSource()).isEqualTo("redis");
        verify(explanationRepository, never())
                .findTopByEventIdAndLanguageAndStyleAndIncludeRecommendedActionsAndEvidenceHashAndCurrentTrue(
                        anyString(), anyString(), anyString(), eq(true), anyString());
    }

    @Test
    void getFallsBackToSqlAndRehydratesRedis() {
        when(redisReadService.readValue(
                CacheKeys.explanationV36Key("evt-5", "hash5", "security_analyst", "en"),
                V36LlmExplanationResponse.class)).thenReturn(Optional.empty());

        LlmExplanation sqlEntity = new LlmExplanation();
        sqlEntity.setEventId("evt-5");
        sqlEntity.setEvidenceHash("hash5");
        sqlEntity.setStyle("security_analyst");
        sqlEntity.setLanguage("en");
        sqlEntity.setIncludeRecommendedActions(true);
        sqlEntity.setExplanationJson("""
                {"schemaVersion":"v3.6.1","eventId":"evt-5","summary":"sql cached","provider":"gemini","fallback":false}
                """);
        when(explanationRepository
                .findTopByEventIdAndLanguageAndStyleAndIncludeRecommendedActionsAndEvidenceHashAndCurrentTrue(
                        "evt-5", "en", "security_analyst", true, "hash5"))
                .thenReturn(Optional.of(sqlEntity));

        Optional<V36LlmExplanationResponse> result = cacheService.get("evt-5", "hash5", "security_analyst", "en");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getCached()).isTrue();
        assertThat(result.orElseThrow().getSource()).isEqualTo("sql_fallback");
        verify(redisReadService).writeJson(
                eq(CacheKeys.explanationV36Key("evt-5", "hash5", "security_analyst", "en")),
                any(), any(Duration.class));
    }

    @Test
    void getBothMissReturnsEmpty() {
        when(redisReadService.readValue(
                CacheKeys.explanationV36Key("evt-6", "hash6", "security_analyst", "en"),
                V36LlmExplanationResponse.class)).thenReturn(Optional.empty());
        when(explanationRepository
                .findTopByEventIdAndLanguageAndStyleAndIncludeRecommendedActionsAndEvidenceHashAndCurrentTrue(
                        "evt-6", "en", "security_analyst", true, "hash6"))
                .thenReturn(Optional.empty());

        Optional<V36LlmExplanationResponse> result = cacheService.get("evt-6", "hash6", "security_analyst", "en");

        assertThat(result).isEmpty();
    }

    @Test
    void putSavesToRedisAndSql() {
        V36LlmExplanationResponse response = new V36LlmExplanationResponse();
        response.setSchemaVersion(CacheKeys.V36_SCHEMA_VERSION);
        response.setEventId("evt-7");
        response.setEvidenceHash("hash7");
        response.setStyle("security_analyst");
        response.setLanguage("en");
        response.setSummary("new explanation");
        response.setProvider("gemini");
        response.setModel("gemini-2.5-flash");
        response.setCached(false);
        response.setSource("generated");
        response.setRecommendedActions(List.of("Open the alert investigation"));

        cacheService.put(response);

        verify(redisReadService).writeJson(
                eq(CacheKeys.explanationV36Key("evt-7", "hash7", "security_analyst", "en")),
                eq(response), any(Duration.class));
        verify(redisReadService).writeJson(
                eq(CacheKeys.explanationV36LatestKey("evt-7")),
                eq(response), any(Duration.class));
        verify(explanationRepository).markPreviousAsNotCurrent("evt-7", "en", "security_analyst", true);
        verify(explanationRepository).save(any(LlmExplanation.class));
    }

    @Test
    void tryAcquireLockSucceeds() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(CacheKeys.explanationLockKey("evt-8", "en", "security_analyst"),
                "locked", Duration.ofSeconds(30))).thenReturn(true);

        boolean result = cacheService.tryAcquireLock("evt-8", "en", "security_analyst", Duration.ofSeconds(30));

        assertThat(result).isTrue();
    }

    @Test
    void tryAcquireLockFailsWhenHeld() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(CacheKeys.explanationLockKey("evt-9", "en", "security_analyst"),
                "locked", Duration.ofSeconds(30))).thenReturn(false);

        boolean result = cacheService.tryAcquireLock("evt-9", "en", "security_analyst", Duration.ofSeconds(30));

        assertThat(result).isFalse();
    }
}
