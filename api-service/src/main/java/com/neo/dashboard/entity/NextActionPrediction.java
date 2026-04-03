package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Read-only mapping of the `next_action_predictions` table containing model
 * output for the next expected user actions.
 */
@Entity
@Table(name = "next_action_predictions")
@Data
@Immutable
public class NextActionPrediction {
    /* Primary key plus the insured/session identifiers tied to the prediction. */
    @Id
    private Long id;

    @Column(name = "insured_id")
    private String insuredId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "predicted_at")
    private Instant predictedAt;

    /* Serialized ordered list of predicted next actions. */
    @Column(name = "top3_actions_json", columnDefinition = "NVARCHAR(MAX)")
    private String top3ActionsJson;
}
