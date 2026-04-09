package com.neo.dashboard.service;

import com.neo.dashboard.dto.NextActionPredictionDto;
import com.neo.dashboard.mapper.NextActionPredictionMapper;
import com.neo.dashboard.repository.NextActionPredictionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Integration test of the Redis-first code path using Testcontainers.
 */
@Testcontainers(disabledWithoutDocker = true)
class NextActionPredictionServiceIntegrationTest {

    private static final String INSURED_ID = "insured-123";
    private static final String REDIS_KEY = "next_actions:" + INSURED_ID;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redisTemplate;
    private NextActionPredictionRepository repository;
    private NextActionPredictionService service;

    @BeforeAll
    static void beforeAll() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void afterAll() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.delete(REDIS_KEY);

        repository = mock(NextActionPredictionRepository.class);
        NextActionPredictionMapper mapper = Mappers.getMapper(NextActionPredictionMapper.class);
        service = new NextActionPredictionService(redisTemplate, new ObjectMapper(), repository, mapper);
    }

    @Test
    void getPredictionUsesRedisBeforeSqlFallback() {
        redisTemplate.opsForValue().set(REDIS_KEY, "[\"VERIFY\",\"UPLOAD\",\"SUBMIT\"]");

        Optional<NextActionPredictionDto> result = service.getPrediction(INSURED_ID);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getInsuredId()).isEqualTo(INSURED_ID);
        assertThat(result.orElseThrow().getTop3Actions()).containsExactly("VERIFY", "UPLOAD", "SUBMIT");
        verifyNoInteractions(repository);
    }
}

