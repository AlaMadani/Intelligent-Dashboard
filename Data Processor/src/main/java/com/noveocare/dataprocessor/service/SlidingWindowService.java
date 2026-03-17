package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlidingWindowService {

    private static final long WINDOW_TTL_MINUTES = 10;
    private static final int MAX_SEQUENCE_LENGTH = 5;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper; // Injection de Jackson pour convertir en JSON

    public void addEventToUserHistory(AuditTrailEvent event) {
        String redisKey = "history:user:" + event.getUserKey();

        try {
            // 1. On transforme l'événement complet en chaîne JSON
            String eventJson = objectMapper.writeValueAsString(event);

            // 2. On ajoute à la fin de la file (à droite)
            redisTemplate.opsForList().rightPush(redisKey, eventJson);

            // 3. LA MAGIE DE LA FENÊTRE GLISSANTE : On ne garde que les 5 derniers !
            // L'index -5 représente le 5ème élément en partant de la fin, -1 est le tout dernier.
            redisTemplate.opsForList().trim(redisKey, -MAX_SEQUENCE_LENGTH, -1);

            // 4. On rafraîchit le chrono d'inactivité
            redisTemplate.expire(redisKey, Duration.ofMinutes(WINDOW_TTL_MINUTES));

        } catch (JsonProcessingException e) {
            log.error("Erreur lors de la sérialisation de l'événement pour Redis", e);
        }
    }

    // Retourne maintenant une liste d'objets complets !
    public List<AuditTrailEvent> getRecentEvents(String userKey, int count) {
        String redisKey = "history:user:" + userKey;

        // Récupère les X derniers éléments en JSON
        List<String> eventsJson = redisTemplate.opsForList().range(redisKey, -count, -1);

        if (eventsJson == null || eventsJson.isEmpty()) {
            return List.of();
        }

        // On les re-transforme en objets Java
        List<AuditTrailEvent> events = new ArrayList<>();
        for (String json : eventsJson) {
            try {
                events.add(objectMapper.readValue(json, AuditTrailEvent.class));
            } catch (JsonProcessingException e) {
                log.error("Erreur lors de la désérialisation de l'événement depuis Redis", e);
            }
        }

        return events;
    }
}