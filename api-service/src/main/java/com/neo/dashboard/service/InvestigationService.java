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

    // On utilise StringRedisTemplate car tes valeurs dans Redis sont des Strings (JSON)
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SecurityAlertSequenceRepository sequenceRepository;

    public List<AuditTrailEvent> getUserHistory(Integer userKey) {
        String key = "history:user:" + userKey;
        List<AuditTrailEvent> history = new ArrayList<>();

        try {
            // Equivalent de: LRANGE history:user:10034 0 -1
            List<String> jsonEvents = redisTemplate.opsForList().range(key, 0, -1);

            if (jsonEvents != null && !jsonEvents.isEmpty()) {
                for (String json : jsonEvents) {
                    // Deserialiser chaque ligne JSON en objet AuditTrailEvent
                    AuditTrailEvent event = objectMapper.readValue(json, AuditTrailEvent.class);
                    history.add(event);
                }
                return history;
            }
        } catch (Exception e) {
            log.error("Erreur lors de la lecture de l'historique Redis pour l'utilisateur {}", userKey, e);
        }

        Optional<SecurityAlertSequence> sequenceOpt = sequenceRepository.findTopByUserKeyOrderByCreatedAtDesc(userKey);
        if (sequenceOpt.isEmpty()) {
            return history;
        }

        SecurityAlertSequence sequence = sequenceOpt.get();
        if (sequence.getSequenceJson() == null || sequence.getSequenceJson().isBlank()) {
            return history;
        }

        try {
            history = parseSequenceJson(sequence.getSequenceJson());
            cacheHistory(key, history);
        } catch (Exception e) {
            log.error("Erreur lors du parsing du sequence_json pour l'utilisateur {}", userKey, e);
        }

        return history;
    }

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

    public PredictionDto getPredictionByAlertId(Long alertId) {
        return sequenceRepository.findByAlert_Id(alertId)
                .map(sequence -> new PredictionDto(sequence.getPredictionJson(), sequence.getPredictionSummary()))
                .orElse(null);
    }

    private List<AuditTrailEvent> parseSequenceJson(String sequenceJson) throws Exception {
        return objectMapper.readValue(sequenceJson, new TypeReference<List<AuditTrailEvent>>() {});
    }

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
