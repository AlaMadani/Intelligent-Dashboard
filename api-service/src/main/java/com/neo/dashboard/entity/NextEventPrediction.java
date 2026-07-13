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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "insured_id", length = 128, nullable = false)
    private String insuredId;

    @Column(name = "session_id", length = 256, nullable = false)
    private String sessionId;

    @Column(name = "context_event_id", length = 128)
    private String contextEventId;

    @Column(name = "context_size", nullable = false)
    private Integer contextSize;

    @Column(name = "model_name", length = 64)
    private String modelName;

    @Column(name = "predictions_json", columnDefinition = "NVARCHAR(MAX)", nullable = false)
    private String predictionsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
