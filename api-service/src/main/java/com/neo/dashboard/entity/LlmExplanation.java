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

/**
 * Stores AI-generated natural-language explanations for detected anomalies.
 * Each record captures the parameters used to request the explanation, the
 * full explanation payload, and lifecycle timestamps for cache management.
 */
@Entity
@Table(name = "llm_explanations")
@Data
public class LlmExplanation {

    /** Unique identifier for the explanation record. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Version of the schema used when this explanation was generated. */
    @Column(name = "schema_version")
    private String schemaVersion;

    /** Identifier of the anomaly event that this explanation refers to. */
    @Column(name = "event_id")
    private String eventId;

    /** External identifier of the insured individual for context. */
    @Column(name = "insured_id")
    private String insuredId;

    /** Identifier of the session in which the explained event occurred. */
    @Column(name = "session_id")
    private String sessionId;

    /** Language code (e.g. en, fr) used for generating the explanation. */
    @Column(name = "language")
    private String language;

    /** Stylistic tone requested for the explanation (e.g. technical, business). */
    @Column(name = "style")
    private String style;

    /** Whether the explanation should include recommended remediation actions. */
    @Column(name = "include_recommended_actions")
    private Boolean includeRecommendedActions;

    /** LLM provider used (e.g. OPENAI, AZURE_OPENAI, ANTHROPIC). */
    @Column(name = "provider")
    private String provider;

    /** Specific model name or version used within the provider. */
    @Column(name = "model")
    private String model;

    /** Version identifier of the prompt template used to generate the explanation. */
    @Column(name = "prompt_version")
    private String promptVersion;

    /** Hash of the evidence data for cache-busting and change detection. */
    @Column(name = "evidence_hash")
    private String evidenceHash;

    /** Full JSON payload of the generated explanation, including structured fields. */
    @Column(name = "explanation_json", columnDefinition = "NVARCHAR(MAX)")
    private String explanationJson;

    /** Condensed textual summary of the explanation for quick display. */
    @Column(name = "summary", columnDefinition = "NVARCHAR(MAX)")
    private String summary;

    /** Whether this is the most current (latest) explanation for the referenced event. */
    @Column(name = "is_current")
    private Boolean current;

    /** How many times an explanation has been regenerated for this event. */
    @Column(name = "generation_count")
    private Integer generationCount;

    /** Timestamp when the explanation record was first created. */
    @Column(name = "created_at")
    private Instant createdAt;

    /** Timestamp when the explanation record was last updated. */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Instant after which the explanation may be evicted from the cache. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * Initialises default values before the entity is first persisted.
     * Sets timestamps to the current instant, marks the explanation as
     * current, and initialises the generation counter to 1.
     */
    @PrePersist
    void onCreate() {
        /* Capture the current time for both creation and update stamps. */
        Instant now = Instant.now();
        if (createdAt == null) {
            this.createdAt = now;
        }
        if (updatedAt == null) {
            this.updatedAt = now;
        }
        /* Mark this record as the current explanation for its event. */
        if (current == null) {
            this.current = true;
        }
        /* Initialise the generation counter to one. */
        if (generationCount == null) {
            this.generationCount = 1;
        }
    }

    /**
     * Updates the modification timestamp whenever the entity is changed.
     */
    @PreUpdate
    void onUpdate() {
        /* Refresh the update timestamp to the current instant. */
        this.updatedAt = Instant.now();
    }
}
