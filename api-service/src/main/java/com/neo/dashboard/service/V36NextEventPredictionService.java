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

/**
 * Service that resolves next-event predictions for a given session or insured
 * entity.  Reads from Redis first, falls back to the database when the cache
 * misses, and returns an "unavailable" response when neither source has data.
 */
@Service
@Slf4j
public class V36NextEventPredictionService {

    /** Reads structured prediction values from Redis caches. */
    private final V36RedisReadService redisReadService;
    /** JPA repository for persisting and querying next-event predictions. */
    private final NextEventPredictionRepository repository;
    /** Jackson mapper for deserialising prediction JSON into DTOs. */
    private final ObjectMapper objectMapper;
    /** Transaction manager used to create isolated read-only transactions for SQL fallback. */
    private final PlatformTransactionManager transactionManager;
    /** Read-only transaction template with REQUIRES_NEW propagation for isolated SQL reads. */
    private TransactionTemplate requiresNewTx;

    /**
     * Constructs the service with the given dependencies and initialises the
     * isolated-transaction template.
     */
    public V36NextEventPredictionService(V36RedisReadService redisReadService,
                                          NextEventPredictionRepository repository,
                                          ObjectMapper objectMapper,
                                          PlatformTransactionManager transactionManager) {
        this.redisReadService = redisReadService;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionManager = transactionManager;
    }

    /**
     * Initialises a read-only transaction template with REQUIRES_NEW propagation
     * so that SQL fallback reads run in their own transaction, independent of any
     * caller's transactional context.
     */
    @PostConstruct
    void initTransactionTemplate() {
        requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNewTx.setReadOnly(true);
    }

    /**
     * Retrieves the next-event prediction for a given session.  Tries Redis
     * first, then SQL, and returns "unavailable" when neither has data.
     *
     * @param sessionId the session identifier
     * @return a prediction DTO (possibly with "unavailable" status)
     */
    public V36NextEventPredictionDto getBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return unavailable("session_id_missing");
        }
        return readRedisSession(sessionId)
                .or(() -> readSqlBySessionId(sessionId))
                .orElseGet(() -> unavailable("next_event_prediction_not_available"));
    }

    /**
     * Retrieves the next-event prediction for a given insured entity.  Tries
     * Redis first, then SQL, and returns "unavailable" when neither has data.
     *
     * @param insuredId the insured identifier
     * @return a prediction DTO (possibly with "unavailable" status)
     */
    public V36NextEventPredictionDto getByInsuredId(String insuredId) {
        if (insuredId == null || insuredId.isBlank()) {
            return unavailable("insured_id_missing");
        }
        return readRedisInsured(insuredId)
                .or(() -> readSqlByInsuredId(insuredId))
                .orElseGet(() -> unavailable("next_event_prediction_not_available"));
    }

    /**
     * Returns the best available next-event prediction for the User 360 view.
     * Prefers the session-level prediction; falls back to the insured-level
     * prediction when the session-level one has no heads.
     *
     * @param insuredId the insured identifier (may be {@code null})
     * @param sessionId the session identifier (may be {@code null})
     * @return the best prediction DTO available
     */
    public V36NextEventPredictionDto getBestForUser360(String insuredId, String sessionId) {
        /* Prefer session-level prediction if available and has heads. */
        if (sessionId != null && !sessionId.isBlank()) {
            V36NextEventPredictionDto sessionPrediction = getBySessionId(sessionId);
            if (sessionPrediction.getHeads() != null && !sessionPrediction.getHeads().isEmpty()) {
                return sessionPrediction;
            }
        }
        /* Fall back to insured-level prediction when session has no heads. */
        if (insuredId != null && !insuredId.isBlank()) {
            V36NextEventPredictionDto insuredPrediction = getByInsuredId(insuredId);
            if (insuredPrediction.getHeads() != null && !insuredPrediction.getHeads().isEmpty()) {
                insuredPrediction.addWarning("fallback_to_insured_prediction");
                return insuredPrediction;
            }
        }
        return unavailable("next_event_prediction_not_available");
    }

    /**
     * Retrieves the next-event prediction for a session that was generated in
     * the context of a specific event.  Filters Redis results by context event
     * ID and falls back to SQL.
     *
     * @param sessionId      the session identifier
     * @param contextEventId the event that provided the context for the prediction
     * @return a contextual prediction DTO or "unavailable"
     */
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

    /**
     * Retrieves a prediction that was computed <em>before</em> a given event
     * occurred (i.e. a prediction whose {@code contextEventId} differs from
     * the supplied {@code eventId}).  This is used to find the prediction
     * that was active at the time the event happened.
     *
     * @param sessionId the session identifier
     * @param eventId   the event that occurred (excluded from matching)
     * @param eventTime the timestamp of the event (reserved for future use)
     * @return a contextual prediction DTO or "unavailable"
     */
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

    /**
     * Reads the session-level prediction from Redis.
     *
     * @param sessionId the session identifier
     * @return an Optional containing the prediction with source set to "redis", or empty
     */
    private Optional<V36NextEventPredictionDto> readRedisSession(String sessionId) {
        String key = CacheKeys.nextEventPredictionSessionKey(sessionId);
        Optional<V36NextEventPredictionDto> result = redisReadService.readValue(key, V36NextEventPredictionDto.class)
                .map(dto -> { dto.setSource("redis"); return dto; });
        if (result.isEmpty()) {
            log.debug("Redis miss for session prediction key={}", key);
        }
        return result;
    }

    /**
     * Reads the insured-level prediction from Redis.
     *
     * @param insuredId the insured identifier
     * @return an Optional containing the prediction with source set to "redis", or empty
     */
    private Optional<V36NextEventPredictionDto> readRedisInsured(String insuredId) {
        String key = CacheKeys.nextEventPredictionInsuredKey(insuredId);
        Optional<V36NextEventPredictionDto> result = redisReadService.readValue(key, V36NextEventPredictionDto.class)
                .map(dto -> { dto.setSource("redis"); return dto; });
        if (result.isEmpty()) {
            log.debug("Redis miss for insured prediction key={}", key);
        }
        return result;
    }

    /**
     * Reads the session-level prediction from the database in an isolated
     * read-only transaction.  Adds a SQL-fallback warning when data is found.
     */
    private Optional<V36NextEventPredictionDto> readSqlBySessionId(String sessionId) {
        return executeIsolated(() -> repository.findTopBySessionIdOrderByCreatedAtDesc(sessionId)
                .map(this::toDto)
                .map(dto -> { dto.addWarning("next_event_prediction_sql_fallback_used"); return dto; }));
    }

    /**
     * Reads the session+context prediction from the database in an isolated
     * read-only transaction.
     */
    private Optional<V36NextEventPredictionDto> readSqlBySessionIdAndContextEventId(String sessionId, String contextEventId) {
        return executeIsolated(() -> repository.findTopBySessionIdAndContextEventIdOrderByCreatedAtDesc(sessionId, contextEventId)
                .map(this::toDto));
    }

    /**
     * Reads the insured-level prediction from the database in an isolated
     * read-only transaction.  Adds a SQL-fallback warning when data is found.
     */
    private Optional<V36NextEventPredictionDto> readSqlByInsuredId(String insuredId) {
        return executeIsolated(() -> repository.findTopByInsuredIdOrderByCreatedAtDesc(insuredId)
                .map(this::toDto)
                .map(dto -> { dto.addWarning("next_event_prediction_sql_fallback_used"); return dto; }));
    }

    /**
     * Executes the given supplier inside a new, isolated read-only transaction
     * so that SQL fallback reads do not interfere with the caller's transaction.
     * Returns {@link Optional#empty()} on any data-access or unexpected error.
     */
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

    /**
     * Converts a {@link NextEventPrediction} entity into a DTO by deserialising
     * its {@code predictionsJson} column and populating entity-level fields.
     *
     * @param entity the JPA entity
     * @return the DTO, or {@code null} if the JSON column is empty or malformed
     */
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

    /** Shortcut to create an "unavailable" prediction with a descriptive warning. */
    private static V36NextEventPredictionDto unavailable(String warning) {
        return V36NextEventPredictionDto.unavailable(warning);
    }
}
