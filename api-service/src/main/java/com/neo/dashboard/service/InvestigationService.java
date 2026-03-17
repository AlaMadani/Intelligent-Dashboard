package com.neo.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.dashboard.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvestigationService {

    // On utilise StringRedisTemplate car tes valeurs dans Redis sont des Strings (JSON)
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public List<AuditTrailEvent> getUserHistory(Integer userKey) {
        String key = "history:user:" + userKey;
        List<AuditTrailEvent> history = new ArrayList<>();

        try {
            // Équivalent de: LRANGE history:user:10034 0 -1
            List<String> jsonEvents = redisTemplate.opsForList().range(key, 0, -1);

            if (jsonEvents != null) {
                for (String json : jsonEvents) {
                    // Désérialiser chaque ligne JSON en objet AuditTrailEvent
                    AuditTrailEvent event = objectMapper.readValue(json, AuditTrailEvent.class);
                    history.add(event);
                }
            }
        } catch (Exception e) {
            log.error("Erreur lors de la lecture de l'historique Redis pour l'utilisateur {}", userKey, e);
        }

        return history;
    }
}