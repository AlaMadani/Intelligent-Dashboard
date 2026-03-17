package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Entity
@Table(name = "security_alerts")
@Data
public class SecurityAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_key")
    private Integer userKey; // Correspond au userKey de l'AuditTrail

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "alert_type")
    private String alertType; // ex: "LSTM_ANOMALY_DETECTED"

    // NOUVEAU : Stocke l'erreur de reconstruction de l'Autoencodeur
    @Column(name = "anomaly_score")
    private Double anomalyScore;

    // NOUVEAU : Stocke le seuil utilisé (0.02779) au moment du déclenchement
    @Column(name = "threshold_used")
    private Double thresholdUsed;

    // MODIFIÉ : columnDefinition = "TEXT" pour éviter que le résumé de Gemini ne soit tronqué
    @Column(name = "ai_explanation", columnDefinition = "TEXT")
    private String aiExplanation;

    // MODIFIÉ : Utilisation d'Instant pour une gestion UTC parfaite
    @Column(name = "detected_at")
    private Instant detectedAt;
}