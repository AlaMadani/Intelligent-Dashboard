package com.neo.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.AuditTrailEvent;
import com.neo.dashboard.dto.PredictionDto;
import com.neo.dashboard.dto.SequenceDetailsDto;
import com.neo.dashboard.entity.SecurityAlertSequence;
import com.neo.dashboard.repository.SecurityAlertSequenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvestigationService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SecurityAlertSequenceRepository sequenceRepository;

    /* Resolve user history from Redis, with SQL fallback and cache refill. */
    public List<AuditTrailEvent> getUserHistory(Integer userKey) {
        String key = "history:user:" + userKey;
        List<AuditTrailEvent> history = new ArrayList<>();

        /* First attempt: Redis list of serialized AuditTrailEvent JSON. */
        try {
            List<String> jsonEvents = redisTemplate.opsForList().range(key, 0, -1);
            if (jsonEvents != null && !jsonEvents.isEmpty()) {
                for (String json : jsonEvents) {
                    AuditTrailEvent event = objectMapper.readValue(json, AuditTrailEvent.class);
                    history.add(event);
                }
                return history;
            }
        } catch (Exception e) {
            log.error("Erreur lors de la lecture de l'historique Redis pour l'utilisateur {}", userKey, e);
        }

        /* Fallback: most recent stored sequence for the user. */
        Optional<SecurityAlertSequence> sequenceOpt = sequenceRepository.findTopByUserKeyOrderByCreatedAtDesc(userKey);
        if (sequenceOpt.isEmpty()) {
            return history;
        }

        SecurityAlertSequence sequence = sequenceOpt.get();
        if (sequence.getSequenceJson() == null || sequence.getSequenceJson().isBlank()) {
            return history;
        }

        /* Parse sequence JSON and backfill the Redis cache. */
        try {
            history = parseSequenceJson(sequence.getSequenceJson());
            cacheHistory(key, history);
        } catch (Exception e) {
            log.error("Erreur lors du parsing du sequence_json pour l'utilisateur {}", userKey, e);
        }

        return history;
    }

    /* Retrieve full sequence details for an alert. */
    public SequenceDetailsDto getSequenceDetailsByAlertId(Long alertId) {
        return sequenceRepository.findByAlert_Id(alertId)
                .map(sequence -> new SequenceDetailsDto(
                        alertId,
                        sequence.getUserKey(),
                        sequence.getSequenceJson(),
                        sequence.getPredictionJson(),
                        sequence.getPredictionSummary(),
                        sequence.getCreatedAt()
                ))
                .orElse(null);
    }

    /* Retrieve parsed sequence events for an alert. */
    public List<AuditTrailEvent> getSequenceEventsByAlertId(Long alertId) {
        Optional<SecurityAlertSequence> sequenceOpt = sequenceRepository.findByAlert_Id(alertId);
        if (sequenceOpt.isEmpty()) {
            return new ArrayList<>();
        }

        SecurityAlertSequence sequence = sequenceOpt.get();
        if (sequence.getSequenceJson() == null || sequence.getSequenceJson().isBlank()) {
            return new ArrayList<>();
        }

        try {
            return parseSequenceJson(sequence.getSequenceJson());
        } catch (Exception e) {
            log.error("Erreur lors du parsing du sequence_json pour l'alerte {}", alertId, e);
            return new ArrayList<>();
        }
    }

    /* Retrieve prediction output for an alert. */
    public PredictionDto getPredictionByAlertId(Long alertId) {
        return sequenceRepository.findByAlert_Id(alertId)
                .map(sequence -> new PredictionDto(sequence.getPredictionJson(), sequence.getPredictionSummary()))
                .orElse(null);
    }

    /* Convert the stored JSON array into typed audit events. */
    private List<AuditTrailEvent> parseSequenceJson(String sequenceJson) throws Exception {
        return objectMapper.readValue(sequenceJson, new TypeReference<List<AuditTrailEvent>>() {});
    }

    /* Serialize events and store them as a Redis list. */
    private void cacheHistory(String key, List<AuditTrailEvent> history) {
        if (history == null || history.isEmpty()) {
            return;
        }

        List<String> jsonEvents = new ArrayList<>(history.size());
        for (AuditTrailEvent event : history) {
            try {
                jsonEvents.add(objectMapper.writeValueAsString(event));
            } catch (Exception e) {
                log.error("Erreur lors de la serialisation d'un event pour le cache Redis", e);
            }
        }

        if (!jsonEvents.isEmpty()) {
            redisTemplate.delete(key);
            redisTemplate.opsForList().rightPushAll(key, jsonEvents);
        }
    }
}
