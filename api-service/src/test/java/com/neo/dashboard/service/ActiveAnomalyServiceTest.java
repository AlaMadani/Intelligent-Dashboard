package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.entity.AnomalyEvent;
import com.neo.dashboard.mapper.AnomalyAlertMapper;
import com.neo.dashboard.repository.AnomalyEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveAnomalyServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final AnomalyEventRepository anomalyEventRepository = mock(AnomalyEventRepository.class);
    private final AnomalyAlertMapper anomalyAlertMapper =
            MapperTestSupport.mapperWithJsonSupport(AnomalyAlertMapper.class);

    private ActiveAnomalyService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        service = new ActiveAnomalyService(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                anomalyEventRepository,
                anomalyAlertMapper
        );
        ReflectionTestUtils.setField(service, "activeAnomalyWindow", Duration.ofMinutes(30));
    }

    @Test
    void getActiveAnomalyIgnoresStaleDatabaseFallback() {
        AnomalyEvent staleEvent = new AnomalyEvent();
        staleEvent.setInsuredId("insured-1");
        staleEvent.setAnomalyType("geo_jump");
        staleEvent.setDetectedAt(Instant.now().minus(Duration.ofHours(2)));
        when(anomalyEventRepository.findTopByInsuredIdOrderByDetectedAtDesc("insured-1"))
                .thenReturn(Optional.of(staleEvent));

        Optional<?> result = service.getActiveAnomaly("insured-1");

        assertThat(result).isEmpty();
    }

    @Test
    void getActiveAnomalyReturnsRecentDatabaseFallback() {
        AnomalyEvent freshEvent = new AnomalyEvent();
        freshEvent.setInsuredId("insured-1");
        freshEvent.setAnomalyType("geo_jump");
        freshEvent.setDetectedAt(Instant.now().minus(Duration.ofMinutes(5)));
        when(anomalyEventRepository.findTopByInsuredIdOrderByDetectedAtDesc("insured-1"))
                .thenReturn(Optional.of(freshEvent));

        Optional<?> result = service.getActiveAnomaly("insured-1");

        assertThat(result).isPresent();
    }
}
