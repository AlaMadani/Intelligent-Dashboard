package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Stores model-generated predictions for the next likely event in a session.
 * Each row represents a single prediction context, with the full set of
 * predicted next events serialised as JSON. Uniqueness is enforced on the
 * combination of session and context event to avoid duplicate predictions.
 */
@Entity
@Table(name = "next_event_predictions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"session_id", "context_event_id"})
}, indexes = {
        @Index(name = "idx_nep_session_id", columnList = "session_id"),
        @Index(name = "idx_nep_insured_id", columnList = "insured_id"),
        @Index(name = "idx_nep_session_insured", columnList = "session_id, insured_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NextEventPrediction {

    /** Unique identifier for the prediction record. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** External identifier of the insured individual whose session is being predicted. */
    @Column(name = "insured_id", length = 128, nullable = false)
    private String insuredId;

    /** Identifier of the session for which the prediction was made. */
    @Column(name = "session_id", length = 256, nullable = false)
    private String sessionId;

    /** Identifier of the event that serves as the context anchor for this prediction. */
    @Column(name = "context_event_id", length = 128)
    private String contextEventId;

    /** Number of past events used as context to generate the prediction. */
    @Column(name = "context_size", nullable = false)
    private Integer contextSize;

    /** Name of the model that produced the prediction. */
    @Column(name = "model_name", length = 64)
    private String modelName;

    /** JSON array of predicted next events with associated probabilities. */
    @Column(name = "predictions_json", columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String predictionsJson;

    /** Timestamp when the prediction record was created. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
