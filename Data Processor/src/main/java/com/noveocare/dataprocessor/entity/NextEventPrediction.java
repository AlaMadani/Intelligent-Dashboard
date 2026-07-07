package com.noveocare.dataprocessor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "next_event_predictions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"session_id", "context_event_id"})
})
@Data
public class NextEventPrediction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "insured_id", nullable = false)
    private String insuredId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "context_event_id")
    private String contextEventId;

    @Column(name = "context_size")
    private int contextSize;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "predictions_json", columnDefinition = "NVARCHAR(MAX)")
    private String predictionsJson;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME2")
    private Instant createdAt;
}
