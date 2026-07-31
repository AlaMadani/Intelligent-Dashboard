package com.neo.dashboard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents an authenticated user of the Neo Dashboard application.
 * Stores credentials, profile information, and the assigned role for
 * authorisation decisions throughout the system.
 */
@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    /** Unique internal identifier for the user. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Email address used as the login credential; must be unique across all users. */
    @Column(nullable = false, unique = true, length = 320)
    private String email;

    /** BCrypt-hashed password for authentication; never stored in plain text. */
    @Column(nullable = false, length = 255)
    private String password;

    /** Display name shown throughout the dashboard UI. */
    @Column(nullable = false, length = 100)
    private String fullName;

    /** Role that determines the user's permissions within the application. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    /** Whether the account is active and can log in; disabled accounts are locked out. */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** Whether the user has confirmed ownership of the email address. */
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    /** Timestamp when the user record was first created. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Timestamp of the most recent update to the user record. */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Initialises timestamps and default values before the entity is first persisted.
     * Ensures creation and update timestamps are set and the account starts as enabled.
     */
    @PrePersist
    protected void onCreate() {
        /* Capture the current time for both creation and update stamps. */
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        enabled = true;
    }

    /**
     * Refreshes the updated-at timestamp whenever the entity is modified.
     */
    @PreUpdate
    protected void onUpdate() {
        /* Set the modification timestamp to the current instant. */
        updatedAt = LocalDateTime.now();
    }

}
