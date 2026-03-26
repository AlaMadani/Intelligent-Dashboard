package com.noveocare.dataprocessor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.entity.SecurityAlert;
import com.noveocare.dataprocessor.entity.SecurityAlertSequence;
import com.noveocare.dataprocessor.repository.SecurityAlertSequenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertSequenceService {

    private final SecurityAlertSequenceRepository securityAlertSequenceRepository;
    private final FutureBehaviorPredictionService predictionService;
    private final ObjectMapper objectMapper;

    public void persistAlertSequence(SecurityAlert alert, List<AuditTrailEvent> events) {
        // Guard against invalid inputs.
        if (alert == null || events == null || events.isEmpty()) {
            return;
        }

        // Serialize the event sequence for storage.
        String sequenceJson;
        try {
            sequenceJson = objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize alert sequence", e);
            return;
        }

        // Generate a lightweight prediction summary from recent behavior.
        FutureBehaviorPredictionService.PredictionResult prediction = predictionService.predict(events);

        // Persist the sequence and prediction metadata with the alert.
        SecurityAlertSequence sequence = new SecurityAlertSequence();
        sequence.setAlert(alert);
        sequence.setUserKey(alert.getUserKey());
        sequence.setSequenceJson(sequenceJson);
        sequence.setPredictionJson(prediction.getJson());
        sequence.setPredictionSummary(prediction.getSummary());
        sequence.setCreatedAt(Instant.now());

        securityAlertSequenceRepository.save(sequence);
    }
}
