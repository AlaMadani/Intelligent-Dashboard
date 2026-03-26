package com.neo.dashboard.controller;

import com.neo.dashboard.dto.AuditTrailEvent;
import com.neo.dashboard.dto.PredictionDto;
import com.neo.dashboard.dto.SequenceDetailsDto;
import com.neo.dashboard.entity.SecurityAlert;
import com.neo.dashboard.repository.SecurityAlertRepository;
import com.neo.dashboard.service.InvestigationService;
import com.neo.dashboard.service.XaiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/soc")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SocController {

    private final SecurityAlertRepository alertRepository;
    private final InvestigationService investigationService;
    private final XaiService xaiService;

    /* List all alerts for the SOC dashboard. */
    @GetMapping("/alerts")
    public ResponseEntity<List<SecurityAlert>> getAllAlerts() {
        return ResponseEntity.ok(alertRepository.findAll());
    }

    /* Get user history from Redis-backed investigation flow. */
    @GetMapping("/redis/user/{userKey}/history")
    public ResponseEntity<List<AuditTrailEvent>> getUserLiveHistory(@PathVariable Integer userKey) {
        List<AuditTrailEvent> history = investigationService.getUserHistory(userKey);
        return ResponseEntity.ok(history);
    }

    /* Get user history (legacy path). */
    @GetMapping("/user/{userKey}/history")
    public ResponseEntity<List<AuditTrailEvent>> getUserHistory(@PathVariable Integer userKey) {
        List<AuditTrailEvent> history = investigationService.getUserHistory(userKey);
        return ResponseEntity.ok(history);
    }

    /* Fetch sequence events associated with a specific alert. */
    @GetMapping("/alerts/{id}/sequence")
    public ResponseEntity<List<AuditTrailEvent>> getAlertSequence(@PathVariable Long id) {
        List<AuditTrailEvent> sequence = investigationService.getSequenceEventsByAlertId(id);
        return ResponseEntity.ok(sequence);
    }

    /* Fetch full sequence details (raw JSON + metadata) for an alert. */
    @GetMapping("/alerts/{id}/sequence-details")
    public ResponseEntity<SequenceDetailsDto> getAlertSequenceDetails(@PathVariable Long id) {
        SequenceDetailsDto details = investigationService.getSequenceDetailsByAlertId(id);
        if (details == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(details);
    }

    /* Fetch prediction output associated with an alert. */
    @GetMapping("/alerts/{id}/prediction")
    public ResponseEntity<PredictionDto> getAlertPrediction(@PathVariable Long id) {
        PredictionDto prediction = investigationService.getPredictionByAlertId(id);
        if (prediction == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(prediction);
    }

    /* Generate or retrieve the AI explanation for an alert. */
    @GetMapping("/alerts/{id}/explain")
    public ResponseEntity<String> getAlertExplanation(@PathVariable Long id) {
        try {
            String explanation = xaiService.explainAlert(id);
            return ResponseEntity.ok(explanation);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erreur lors de l'analyse : " + e.getMessage());
        }
    }
}
