package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "llm_explanations")
@Data
public class LlmExplanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schema_version")
    private String schemaVersion;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "insured_id")
    private String insuredId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "language")
    private String language;

    @Column(name = "style")
    private String style;

    @Column(name = "include_recommended_actions")
    private Boolean includeRecommendedActions;

    @Column(name = "provider")
    private String provider;

    @Column(name = "model")
    private String model;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "evidence_hash")
    private String evidenceHash;

    @Column(name = "explanation_json", columnDefinition = "NVARCHAR(MAX)")
    private String explanationJson;

    @Column(name = "summary", columnDefinition = "NVARCHAR(MAX)")
    private String summary;

    @Column(name = "is_current")
    private Boolean current;

    @Column(name = "generation_count")
    private Integer generationCount;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            this.createdAt = now;
        }
        if (updatedAt == null) {
            this.updatedAt = now;
        }
        if (current == null) {
            this.current = true;
        }
        if (generationCount == null) {
            this.generationCount = 1;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
