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
@CrossOrigin(origins = "*") // A restreindre plus tard pour la securite
public class SocController {

    private final SecurityAlertRepository alertRepository;
    private final InvestigationService investigationService;
    private final XaiService xaiService;
    // 1. Exposer toutes les alertes (Pour le Dashboard principal)
    @GetMapping("/alerts")
    public ResponseEntity<List<SecurityAlert>> getAllAlerts() {
        // Idealement, rajouter une pagination ici dans le futur
        return ResponseEntity.ok(alertRepository.findAll());
    }

    // 2. L'idee de genie : L'investigation Redis !
    @GetMapping("/redis/user/{userKey}/history")
    public ResponseEntity<List<AuditTrailEvent>> getUserLiveHistory(@PathVariable Integer userKey) {
        List<AuditTrailEvent> history = investigationService.getUserHistory(userKey);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/user/{userKey}/history")
    public ResponseEntity<List<AuditTrailEvent>> getUserHistory(@PathVariable Integer userKey) {
        List<AuditTrailEvent> history = investigationService.getUserHistory(userKey);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/alerts/{id}/sequence")
    public ResponseEntity<List<AuditTrailEvent>> getAlertSequence(@PathVariable Long id) {
        List<AuditTrailEvent> sequence = investigationService.getSequenceEventsByAlertId(id);
        return ResponseEntity.ok(sequence);
    }

    @GetMapping("/alerts/{id}/sequence-details")
    public ResponseEntity<SequenceDetailsDto> getAlertSequenceDetails(@PathVariable Long id) {
        SequenceDetailsDto details = investigationService.getSequenceDetailsByAlertId(id);
        if (details == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(details);
    }

    @GetMapping("/alerts/{id}/prediction")
    public ResponseEntity<PredictionDto> getAlertPrediction(@PathVariable Long id) {
        PredictionDto prediction = investigationService.getPredictionByAlertId(id);
        if (prediction == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(prediction);
    }

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
