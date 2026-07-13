package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.v36.V36NextEventPredictionDto;
import com.neo.dashboard.entity.NextEventPrediction;
import com.neo.dashboard.redis.CacheKeys;
import com.neo.dashboard.repository.NextEventPredictionRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@Slf4j
public class V36NextEventPredictionService {

    private final V36RedisReadService redisReadService;
    private final NextEventPredictionRepository repository;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private TransactionTemplate requiresNewTx;

    public V36NextEventPredictionService(V36RedisReadService redisReadService,
                                          NextEventPredictionRepository repository,
                                          ObjectMapper objectMapper,
                                          PlatformTransactionManager transactionManager) {
        this.redisReadService = redisReadService;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionManager = transactionManager;
    }

    @PostConstruct
    void initTransactionTemplate() {
        requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTx.setReadOnly(true);
    }

    public V36NextEventPredictionDto getBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return unavailable("session_id_missing");
        }
        return readRedisSession(sessionId)
                .or(() -> readSqlBySessionId(sessionId))
                .orElseGet(() -> unavailable("next_event_prediction_not_available"));
    }

    public V36NextEventPredictionDto getByInsuredId(String insuredId) {
        if (insuredId == null || insuredId.isBlank()) {
            return unavailable("insured_id_missing");
        }
        return readRedisInsured(insuredId)
                .or(() -> readSqlByInsuredId(insuredId))
                .orElseGet(() -> unavailable("next_event_prediction_not_available"));
    }

    public V36NextEventPredictionDto getBestForUser360(String insuredId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            V36NextEventPredictionDto sessionPrediction = getBySessionId(sessionId);
            if (sessionPrediction.getHeads() != null && !sessionPrediction.getHeads().isEmpty()) {
                return sessionPrediction;
            }
        }
        if (insuredId != null && !insuredId.isBlank()) {
            V36NextEventPredictionDto insuredPrediction = getByInsuredId(insuredId);
            if (insuredPrediction.getHeads() != null && !insuredPrediction.getHeads().isEmpty()) {
                insuredPrediction.addWarning("fallback_to_insured_prediction");
                return insuredPrediction;
            }
        }
        return unavailable("next_event_prediction_not_available");
    }

    public V36NextEventPredictionDto getPredictionByContextEventId(String sessionId, String contextEventId) {
        if (sessionId == null || sessionId.isBlank()) {
            return unavailable("session_id_missing");
        }
        if (contextEventId == null || contextEventId.isBlank()) {
            return unavailable("context_event_id_missing");
        }
        return readRedisSession(sessionId)
                .filter(p -> contextEventId.equals(p.getContextEventId()))
                .or(() -> readSqlBySessionIdAndContextEventId(sessionId, contextEventId))
                .map(p -> { p.setSource("contextual"); return p; })
                .orElseGet(() -> {
                    V36NextEventPredictionDto unavailable = unavailable("next_event_prediction_not_available");
                    unavailable.addWarning("prediction_not_available_for_event");
                    return unavailable;
                });
    }

    public V36NextEventPredictionDto getPredictionBeforeEvent(String sessionId, String eventId, Instant eventTime) {
        if (sessionId == null || sessionId.isBlank()) {
            return unavailable("session_id_missing");
        }
        return readRedisSession(sessionId)
                .filter(p -> eventId == null || !eventId.equals(p.getContextEventId()))
                .or(() -> readSqlBySessionId(sessionId))
                .map(p -> { p.setSource("contextual"); return p; })
                .orElseGet(() -> {
                    V36NextEventPredictionDto unavailable = unavailable("next_event_prediction_not_available");
                    unavailable.addWarning("contextual_prediction_not_available");
                    return unavailable;
                });
    }

    private Optional<V36NextEventPredictionDto> readRedisSession(String sessionId) {
        String key = CacheKeys.nextEventPredictionSessionKey(sessionId);
        Optional<V36NextEventPredictionDto> result = redisReadService.readValue(key, V36NextEventPredictionDto.class)
                .map(dto -> { dto.setSource("redis"); return dto; });
        if (result.isEmpty()) {
            log.debug("Redis miss for session prediction key={}", key);
        }
        return result;
    }

    private Optional<V36NextEventPredictionDto> readRedisInsured(String insuredId) {
        String key = CacheKeys.nextEventPredictionInsuredKey(insuredId);
        Optional<V36NextEventPredictionDto> result = redisReadService.readValue(key, V36NextEventPredictionDto.class)
                .map(dto -> { dto.setSource("redis"); return dto; });
        if (result.isEmpty()) {
            log.debug("Redis miss for insured prediction key={}", key);
        }
        return result;
    }

    private Optional<V36NextEventPredictionDto> readSqlBySessionId(String sessionId) {
        return executeIsolated(() -> repository.findTopBySessionIdOrderByCreatedAtDesc(sessionId)
                .map(this::toDto)
                .map(dto -> { dto.addWarning("next_event_prediction_sql_fallback_used"); return dto; }));
    }

    private Optional<V36NextEventPredictionDto> readSqlBySessionIdAndContextEventId(String sessionId, String contextEventId) {
        return executeIsolated(() -> repository.findTopBySessionIdAndContextEventIdOrderByCreatedAtDesc(sessionId, contextEventId)
                .map(this::toDto));
    }

    private Optional<V36NextEventPredictionDto> readSqlByInsuredId(String insuredId) {
        return executeIsolated(() -> repository.findTopByInsuredIdOrderByCreatedAtDesc(insuredId)
                .map(this::toDto)
                .map(dto -> { dto.addWarning("next_event_prediction_sql_fallback_used"); return dto; }));
    }

    private Optional<V36NextEventPredictionDto> executeIsolated(Supplier<Optional<V36NextEventPredictionDto>> supplier) {
        try {
            return requiresNewTx.execute(status -> supplier.get());
        } catch (DataAccessException | TransactionException e) {
            log.warn("Isolated SQL read failed for next event prediction", e);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Unexpected error in isolated SQL read for next event prediction", e);
            return Optional.empty();
        }
    }

    private V36NextEventPredictionDto toDto(NextEventPrediction entity) {
        String predictionsJson = entity.getPredictionsJson();
        if (predictionsJson == null || predictionsJson.isBlank()) {
            return null;
        }
        try {
            V36NextEventPredictionDto dto = objectMapper.readValue(predictionsJson, V36NextEventPredictionDto.class);
            dto.setInsuredId(entity.getInsuredId());
            dto.setSessionId(entity.getSessionId());
            dto.setContextEventId(entity.getContextEventId());
            dto.setContextSize(entity.getContextSize());
            dto.setModel(entity.getModelName());
            dto.setCreatedAt(entity.getCreatedAt());
            dto.setSource("sql_fallback");
            if (dto.getHeads() == null) {
                dto.setHeads(Map.of());
            }
            return dto;
        } catch (Exception e) {
            log.warn("Failed to parse predictions_json for id={}", entity.getId(), e);
            return null;
        }
    }

    private static V36NextEventPredictionDto unavailable(String warning) {
        return V36NextEventPredictionDto.unavailable(warning);
    }
}
