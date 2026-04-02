package com.noveocare.dataprocessor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Latest next-action recommendation snapshot stored per insured user.
 */
@Entity
@Table(name = "next_action_predictions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"insured_id"})
})
@Data
public class NextActionPrediction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // User and session context for the prediction snapshot.
    @Column(name = "insured_id", nullable = false)
    private String insuredId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "predicted_at")
    private Instant predictedAt;

    @Column(name = "top3_actions_json", columnDefinition = "NVARCHAR(MAX)")
    private String top3ActionsJson;
}
